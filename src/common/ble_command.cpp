// ============================================================================
//  BLE 控制通道的命令处理
//
//  和 HTTP 那套接口**能力对齐**,但只暴露必要的操作 ——
//  BLE 带宽远小于 WiFi,不适合做通用 HTTP 转发。
// ============================================================================
#include "ble_command.h"

#include "script_engine.h"
#include "led.h"
#include "net_manager.h"

// ---------------------------------------------------------------- 异步保存队列
//  ⚠️ 为什么要有这个队列:
//     写板子的 Flash 要 3~9 秒(实测)。而 BLE 的响应是在处理函数返回之后才发的,
//     所以同步写会让**整条控制通道被堵几十秒** —— 一次"同步服务器"写 7 条,
//     期间 App 连流程列表都读不出来(排队的 LIST 活活饿死)。
//     改成:SAVE 只入队、**立刻回 ok**;主循环里再慢慢落盘。
static const int SAVE_Q_MAX = 12;
static String    s_saveName[SAVE_Q_MAX];
static String    s_saveBody[SAVE_Q_MAX];
static volatile int s_saveHead = 0, s_saveTail = 0, s_saveCount = 0;

static bool queueSave(const String &name, const String &body) {
  if (s_saveCount >= SAVE_Q_MAX) return false;
  s_saveName[s_saveTail] = name;
  s_saveBody[s_saveTail] = body;
  s_saveTail = (s_saveTail + 1) % SAVE_Q_MAX;
  s_saveCount++;
  return true;
}

/** 主循环里调:一次落盘一条(避免长时间占着 CPU) */
void bleServiceSaveQueue() {
  if (s_saveCount <= 0) return;
  String name = s_saveName[s_saveHead];
  String body = s_saveBody[s_saveHead];
  s_saveHead = (s_saveHead + 1) % SAVE_Q_MAX;
  s_saveCount--;
  char errBuf[80] = {0};
  if (Scripts.saveFromText(name.c_str(), body.c_str(), errBuf, sizeof(errBuf))) {
    Serial.printf("[ble] 异步落盘完成:%s\n", name.c_str());
  } else {
    Serial.printf("[ble] 异步落盘失败:%s — %s\n", name.c_str(), errBuf);
  }
}

/** 把一行文本转义成 JSON 字符串(不带头尾引号) */
static String esc(const String &in) {
  String o;
  o.reserve(in.length() + 8);
  for (size_t i = 0; i < in.length(); i++) {
    char c = in[i];
    switch (c) {
      case '"':  o += "\\\""; break;
      case '\\': o += "\\\\"; break;
      case '\n': o += "\\n";  break;
      case '\r': o += "\\r";  break;
      case '\t': o += "\\t";  break;
      default:
        if ((unsigned char)c < 0x20) { o += ' '; }
        else o += c;
    }
  }
  return o;
}

/** 统一的错误响应(和 HTTP 的 {"message":"..."} 同形) */
static String err(const String &msg) {
  return "{\"ok\":false,\"message\":\"" + esc(msg) + "\"}";
}

/** 取第 n 个参数(以空格分) */
static String argAt(const String &s, int n) {
  int i = 0, start = -1, idx = 0;
  for (; i <= (int)s.length(); i++) {
    bool sep = (i == (int)s.length()) || s[i] == ' ';
    if (!sep) { if (start < 0) start = i; }
    else if (start >= 0) {
      if (idx == n) return s.substring(start, i);
      idx++; start = -1;
    }
  }
  return "";
}

String bleHandleCommand(const String &rawReq) {
  String req = rawReq;
  req.trim();
  if (req.isEmpty()) return err("空请求");

  int sp = req.indexOf(' ');
  String op = (sp < 0 ? req : req.substring(0, sp));
  String rest = (sp < 0 ? "" : req.substring(sp + 1));
  op.toUpperCase();

  // ---------------------------------------------------------------- 心跳
  if (op == "PING") return "PONG";

  // ---------------------------------------------------------------- 状态
  if (op == "STATUS") return netManager().statusJson();

  // ---------------------------------------------------------------- 脚本列表
  if (op == "LIST") {
    String j = "{\"scripts\":[";
    for (uint8_t i = 0; i < Scripts.count(); i++) {
      const Script *sc = Scripts.at(i);
      if (!sc) continue;
      if (i) j += ",";
      j += "{\"index\":" + String((int)i) + ",";
      j += "\"name\":\"" + esc(String(sc->name)) + "\",";
      j += "\"steps\":" + String((int)sc->stepCount) + "}";
    }
    j += "]}";
    return j;
  }

  // ---------------------------------------------------------------- 读一个流程
  if (op == "GET") {
    int idx = argAt(rest, 0).toInt();
    const Script *sc = Scripts.at(idx);
    if (!sc) return err("没有这个流程");
    String j = "{\"name\":\"" + esc(String(sc->name)) + "\",";
    j += "\"steps\":" + String((int)sc->stepCount) + ",";
    j += "\"text\":\"" + esc(netManager().scriptText((uint8_t)idx)) + "\"}";
    return j;
  }

  // ---------------------------------------------------------------- 跑 / 停
  if (op == "RUN") {
    int idx = argAt(rest, 0).toInt();
    const Script *sc = Scripts.at(idx);
    if (!sc) return err("没有这个流程");
    // 借 HTTP 那条路起 —— 和网页点「运行」是同一套
    netManager().requestRun((uint8_t)idx);
    // ⚠️ 响应刻意做成和 /api/run **一模一样** ——
    //    App 那边直接换传输层就行,解析代码一行都不用改
    return "{\"ok\":true,\"message\":\"已排队执行 " + esc(String(sc->name)) + " x1\"}";
  }
  if (op == "STOP") {
    Scripts.requestStop();                 // 打断正在跑的流程
    netManager().stopSchedulePublic();     // 停掉定时/频率循环
    return "{\"ok\":true,\"message\":\"已请求停止(当前步骤结束后生效)\"}";
  }

  // ---------------------------------------------------------------- 页面推送
  //  ⚠️ 这条是给 waitfor/ifpage 用的 —— 页面识别开着时 App 每 700ms 推一次。
  //     控制走蓝牙时,这条也必须走蓝牙,否则板子的 waitfor 永远超时。
  //  格式:PAGE <activity>\n<页面文字>
  if (op == "PAGE") {
    int nl = rest.indexOf('\n');
    String act = (nl < 0) ? rest : rest.substring(0, nl);
    String txt = (nl < 0) ? "" : rest.substring(nl + 1);
    setPageInfo(act.c_str(), txt.c_str());
    return "{\"ok\":true}";
  }

  // ---------------------------------------------------------------- 点灯
  //   LED r g b [ms]   设颜色;不传参数 = 找板子模式
  if (op == "LED") {
    // ⚠️ 同 /api/led:运行中禁止**远程**点灯(蓝牙控制通道也算远程)。
    //    串口那条不拦 —— 那是本地调试。
    if (netManager().isRunning()) {
      return err("板子正在执行流程,已禁止远程改灯(等它跑完再试)");
    }
    if (rest.length() == 0) {
      Led::identify();
      return "{\"ok\":true,\"message\":\"已进入找板子模式(灯闪 60 秒)\"}";
    }
    if (rest.equalsIgnoreCase("TEST")) {
      Led::colorTest();
      return "{\"ok\":true,\"message\":\"四色自检已启动:红→黄→绿→灭,各 1.5 秒\"}";
    }
    if (rest.equalsIgnoreCase("AUTO")) {
      Led::autoMode();
      return "{\"ok\":true,\"message\":\"已回到自动(未连接蓝/已连接绿/执行中黄)\"}";
    }
    if (rest.equalsIgnoreCase("OFF")) {
      Led::off();
      return "{\"ok\":true,\"message\":\"已关灯(保持熄灭)\"}";
    }
    int r = 0, g = 0, b = 0, ms = 0;
    int n = sscanf(rest.c_str(), "%d %d %d %d", &r, &g, &b, &ms);
    if (n < 3) return err("用法: LED r g b [ms]");
    Led::set((uint8_t)(r < 0 ? 0 : r > 255 ? 255 : r),
             (uint8_t)(g < 0 ? 0 : g > 255 ? 255 : g),
             (uint8_t)(b < 0 ? 0 : b > 255 ? 255 : b), (uint32_t)ms);
    return "{\"ok\":true,\"message\":\"LED 已设置\"}";
  }

  // ---------------------------------------------------------------- 日志
  // ⚠️⚠️ BLE 上日志**只回最后几条** ——
  //     实测 MTU 255 时,单次通知最多 247 字节。
  //     整份日志(约 1KB)要分 6 片,而**片多了必然丢片**(链路特性),
  //     丢一片整帧就收不全 ⇒ 请求超时。
  //     只回最后 N 行(默认 8 行,约 200 字节)⇒ **永远单片** ⇒ 稳定。
  //     要看完整日志走 WiFi,或者用 BLE 的 "LOG 30" 明确多要。
  if (op == "LOG") {
    int n = argAt(rest, 0).toInt();
    if (n <= 0 || n > 40) n = 8;                 // 默认 8 行
    return netManager().logsJsonTail(n);
  }
  if (op == "LOGCLR") { netManager().clearLogs(); return "{\"ok\":true}"; }

  // ---------------------------------------------------------------- 存一个流程
  //   格式:SAVE <index>\n<正文>
  //   index = -1 表示新建(正文第一行是流程名)
  // ---------------------------------------------------------------- 异步保存
  //  ⚠️⚠️ **SAVE 必须异步** ——
  //     写 Flash 要 3~9 秒(实测),而 BLE 响应是在处理函数返回后才发的。
  //     同步写的话:一次"同步服务器"要写 7 条 ⇒ 通道被堵 20~60 秒,
  //     期间 App 连流程列表都读不出来(排队的 LIST 活活饿死)。
  //     所以:这里只把请求排进队列、**立刻返回 ok**,
  //     真正的落盘放到主循环里做(见 netServiceScripts 里的 pendingSave)。
  if (op == "SAVE") {
    int nl = rest.indexOf('\n');
    if (nl < 0) return err("缺少正文(SAVE <index>\\n<正文>)");
    int idx = rest.substring(0, nl).toInt();
    String body = rest.substring(nl + 1);
    char errBuf[80] = {0};   // ⚠️ 别叫 err —— 会遮住上面的 err() 函数
    String name;
    if (idx < 0) {
      int e = body.indexOf('\n');
      if (e < 0) return err("新建时正文第一行要是流程名");
      name = body.substring(0, e);
      body = body.substring(e + 1);
    } else {
      const Script *sc = Scripts.at(idx);
      if (!sc) return err("没有这个流程");
      name = String(sc->name);
    }
    name.trim();
    if (name.isEmpty()) return err("流程名为空");
    // 入队前先同步校验(只解析不写盘,几毫秒):
    // 以前直接回"已接受",语法错的流程在落盘时才失败,App 却以为保存成功了
    if (!ScriptEngine::validate(name.c_str(), body.c_str(), errBuf, sizeof(errBuf))) {
      return err(String("流程有错,未保存:") + errBuf);
    }
    if (Scripts.fullFor(name.c_str())) {
      return err("流程数已达上限 " + String((int)Scripts.capacity()) + " 个,请先删掉不用的");
    }
    if (!queueSave(name, body)) {
      return err("保存队列满,请稍后重试");
    }
    return "{\"ok\":true,\"message\":\"已接受保存 " + esc(name) + "(落盘中)\"}";
  }

  // ---------------------------------------------------------------- 删除
  if (op == "DEL") {
    String name = argAt(rest, 0);
    if (name.isEmpty()) return err("缺少流程名");
    return Scripts.remove(name.c_str())
        ? "{\"ok\":true,\"message\":\"已删除 " + esc(name) + "\"}"
        : err("删除失败(不存在?)");
  }

  return err("未知命令:" + op);
}
