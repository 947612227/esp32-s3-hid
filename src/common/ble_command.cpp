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
    if (rest.length() == 0) {
      Led::identify();
      return "{\"ok\":true,\"message\":\"已进入找板子模式(灯闪 60 秒)\"}";
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
    if (!Scripts.saveFromText(name.c_str(), body.c_str(), errBuf, sizeof(errBuf))) {
      return err(String(errBuf));
    }
    return "{\"ok\":true,\"message\":\"已保存 " + esc(name) + "\"}";
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
