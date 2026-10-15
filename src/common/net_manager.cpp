// ============================================================================
//  NetManager 实现:WiFi + Web 管理界面
// ============================================================================
#include "net_manager.h"

#include <LittleFS.h>
#include <WebServer.h>
#include <Update.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <mbedtls/sha256.h>
#include <esp_mac.h>

#include "hid_report_map.h"
#include "hid_touch.h"
#include "led.h"
#include "led.h"
#include "log_buffer.h"
#include "script_engine.h"
#include "web_ui.h"

// WiFi 凭据:secrets.h 不进版本库(见 secrets.example.h)
#if __has_include("../../secrets.h")
#include "../../secrets.h"
#else
#warning "缺少 secrets.h,使用占位凭据(会回退到热点模式)"
#define WIFI_SSID "CHANGE_ME"
#define WIFI_PASS "CHANGE_ME"
#endif

// 板子名字前缀:蓝牙名 / 热点名 / 心跳名都是「前缀_MAC 后两字节」(如 ESP32-S3_098C)
#ifndef BOARD_NAME_PREFIX
#define BOARD_NAME_PREFIX "ESP32-S3"
#endif
#ifndef AP_PASS
#define AP_PASS "12345678"
#endif

static const char *TAG = "[net]";

#define HTTP_PORT 80
static WebServer s_server(HTTP_PORT);
static NetManager s_net;

// ---------------------------------------------------------------------------
//  脚本执行请求 / 状态 / 调度
// ---------------------------------------------------------------------------
static volatile bool s_hasRequest = false;
static RunRequest    s_request;
static RunState      s_state;

static bool     s_schedActive = false;
static uint8_t  s_schedIndex = 0;          // 每次执行前按 s_schedName 重新解析
// ⚠️ 定时循环按**名字**记:以前只记序号,循环期间删掉排在前面的流程,
//    序号整体前移 ⇒ 下一轮跑的就是另一个流程了
static char     s_schedName[SCRIPT_NAME_LEN] = {0};
static uint32_t s_schedIntervalMs = 100;   // P9:频率单位改为毫秒,默认 100ms
static uint32_t s_schedNextAt = 0;
// 连续执行时长(0 = 不限,一直跑到手动停)
// 放在板子侧而不是 App 侧:App 被杀掉也能按时停,不会一直点下去。
static uint32_t s_schedDurationMs = 0;
static uint32_t s_schedEndAt = 0;

// 板子名字 / 备注(实现见「板子名字 + 备注」一节;/api/status 也要用)
#define REMARK_MAX_BYTES 60                 // 服务器限 20 个字;中文一字 3 字节
static char s_boardName[20] = {0};          // ESP32-S3_XXXX(懒加载)
static char s_remark[REMARK_MAX_BYTES + 4] = {0};
static bool s_remarkPending = false;        // 板子上改的、还没被服务器确认

void requestScriptRun(uint8_t index, uint16_t repeat, uint32_t intervalMs) {
  s_request.index = (int)index;
  s_request.repeat = repeat ? repeat : 1;
  s_request.intervalMs = intervalMs;
  s_hasRequest = true;
}

bool takeScriptRunRequest(RunRequest &req) {
  if (!s_hasRequest) return false;
  req = s_request;
  s_hasRequest = false;
  return true;
}

const RunState &scriptRunState() { return s_state; }
void requestScriptStop() { Scripts.requestStop(); }
bool scheduleActive() { return s_schedActive; }

void requestSchedule(int index, uint32_t intervalMs, uint32_t durationMs) {
  s_schedIndex = (uint8_t)index;
  const Script *target = Scripts.at(s_schedIndex);
  strncpy(s_schedName, target ? target->name : "", sizeof(s_schedName) - 1);
  // 频率下限 10ms:实测 BLE 单次报文最小间隔约 11ms,再小没有意义
  s_schedIntervalMs = intervalMs < 10 ? 10 : intervalMs;
  s_schedDurationMs = durationMs;                 // 0 = 不限
  s_schedActive = true;
  s_schedNextAt = millis() + 500;
  s_schedEndAt = durationMs ? (millis() + durationMs) : 0;
  if (durationMs) {
    // ⚠️ 单位是**毫秒**,以前写成 %us 看起来像秒(实测发现的显示 bug)
    LOGF("%s 定时循环已开启:脚本 #%d,每 %u 毫秒,连续 %u 秒", TAG, index,
         (unsigned)s_schedIntervalMs, (unsigned)(durationMs / 1000));
  } else {
    LOGF("%s 定时循环已开启:脚本 #%d,每 %u 毫秒(不限时长)", TAG, index,
         (unsigned)s_schedIntervalMs);
  }
}

/** 定时循环剩余毫秒(0 = 不限或未开启) */
uint32_t scheduleRemainMs() {
  if (!s_schedActive || !s_schedEndAt) return 0;
  int32_t left = (int32_t)(s_schedEndAt - millis());
  return left > 0 ? (uint32_t)left : 0;
}

void requestScheduleStop() {
  if (!s_schedActive) return;
  s_schedActive = false;
  s_schedEndAt = 0;
  s_schedDurationMs = 0;
  LOGF("%s 定时循环已停止", TAG);
}

// ---------------------------------------------------------------------------
//  工具
// ---------------------------------------------------------------------------
static String jsonEscape(const String &in) {
  String o;
  o.reserve(in.length() + 16);
  for (size_t i = 0; i < in.length(); i++) {
    char c = in[i];
    switch (c) {
      case '"':  o += "\\\""; break;
      case '\\': o += "\\\\"; break;
      case '\n': o += "\\n";  break;
      case '\r': break;
      case '\t': o += "\\t";  break;
      default:
        if ((uint8_t)c < 0x20) o += ' '; else o += c;
    }
  }
  return o;
}

static String stepToText(const ScriptStep &st) {
  char buf[128];
  switch (st.op) {
    case StepOp::Tap:
    case StepOp::DoubleTap:
    case StepOp::Hold:
      snprintf(buf, sizeof(buf), "%s (%.2f, %.2f) %ums", stepOpName(st.op), st.a,
               st.b, (unsigned)st.ms);
      break;
    case StepOp::Swipe:
      snprintf(buf, sizeof(buf), "swipe (%.2f,%.2f)->(%.2f,%.2f) %ums", st.a,
               st.b, st.c, st.d, (unsigned)st.ms);
      break;
    case StepOp::Curve:
      snprintf(buf, sizeof(buf), "curve (%.2f,%.2f) c(%.2f,%.2f) ->(%.2f,%.2f)",
               st.a, st.b, st.c, st.d, (float)st.e / 255.0f, (float)st.f / 255.0f);
      break;
    case StepOp::TwoFinger:
      snprintf(buf, sizeof(buf), "twofinger (%.2f,%.2f)->(%.2f,%.2f) gap %.2f",
               st.a, st.b, st.c, st.d, (float)st.e / 255.0f);
      break;
    case StepOp::Pinch:
      snprintf(buf, sizeof(buf), "pinch c(%.2f,%.2f) %.2f->%.2f", st.a, st.b,
               st.c, st.d);
      break;
    case StepOp::Key:
      snprintf(buf, sizeof(buf), "key usage=%u (Android 上不生效)",
               (unsigned)st.e);
      break;
    case StepOp::Consumer:
      snprintf(buf, sizeof(buf), "consumer usage=%u", (unsigned)st.e);
      break;
    case StepOp::Delay:
      snprintf(buf, sizeof(buf), "wait %ums", (unsigned)st.ms);
      break;
    case StepOp::Goto:
      snprintf(buf, sizeof(buf), "goto -> step %u", (unsigned)st.next);
      break;
    case StepOp::Repeat:
      snprintf(buf, sizeof(buf), "repeat x%u (到 endloop 为止)", (unsigned)st.repeat);
      break;
    case StepOp::WaitFor:
      snprintf(buf, sizeof(buf), "waitfor \"%s\" (最多 %ums)", st.note, (unsigned)st.ms);
      break;
    case StepOp::IfPage:
      snprintf(buf, sizeof(buf), "ifpage \"%s\"", st.note);
      break;
    case StepOp::Else:
      snprintf(buf, sizeof(buf), "else");
      break;
    case StepOp::EndIf:
      snprintf(buf, sizeof(buf), "endif");
      break;
    case StepOp::EndLoop:
      snprintf(buf, sizeof(buf), "endloop");
      break;
    case StepOp::Break:
      snprintf(buf, sizeof(buf), "break (跳出循环)");
      break;
    case StepOp::Notify:
      snprintf(buf, sizeof(buf), "notify \"%s\" (通知服务器)", st.note);
      break;
    default:
      snprintf(buf, sizeof(buf), "?");
  }
  // 有备注就附上,网页/App 上能直接看懂这一步在做什么。
  // waitfor / ifpage 的关键字已经在上面的文本里了,不用再附一遍。
  String out(buf);
  // ⚠️ Notify 的 note 就是消息本身,上面已经输出过了,
    //    再附一遍会变成 "notify 内容 内容"(实测踩过,和 waitfor 那次同一个坑)。
    // ⚠️ 这些指令的 note 已经在上面那行里输出过了,不能再附一遍 ——
    //    WaitFor/IfPage 是关键字、Notify 是消息、Goto 是标号名。
    //    漏一个就会变成 `goto 结束 结束` / `waitfor homepage homepage`
    //    (这两个坑都实际踩过)。
    bool noteShown = (st.op == StepOp::WaitFor || st.op == StepOp::IfPage
                      || st.op == StepOp::Notify || st.op == StepOp::Goto);
  if (!noteShown && st.note[0] != '\0') {
    out += "  «";
    out += st.note;
    out += "»";
  }
  return out;
}

// 去掉每个数字小数部分末尾多余的 0(只处理 "数字.数字" 这种片段)
//   0.9230 → 0.923  ·  0.2000 → 0.2  ·  0.0000 → 0.0
// 目的:让"写进去的"和"读出来的"格式一致 —— 用户写 0.923,
//       读回来也应该是 0.923,而不是 0.9230(会让人以为精度变了)。
static void trimTrailingZeros(char *s) {
  char *p = s;
  while (*p) {
    if (*p != '.') { p++; continue; }

    char *q = p + 1;
    char *lastNonZero = p;
    while (*q >= '0' && *q <= '9') {
      if (*q != '0') lastNonZero = q;
      q++;
    }
    if (q > p + 1) {
      char *keepEnd = (lastNonZero == p) ? (p + 2) : (lastNonZero + 1);
      if (keepEnd < q) {
        memmove(keepEnd, q, strlen(q) + 1);
        // ⚠️ memmove 之后 q 已经失效,必须从删除位置继续,
        //    否则下一个数字会被跳过(实测:0.5 处理了,0.2500 没处理)。
        p = keepEnd;
        continue;
      }
    }
    p = q;
  }
}

static String scriptToText(const Script &sc) {
  String out;
  out.reserve(sc.stepCount * 32);
  for (uint8_t i = 0; i < sc.stepCount; i++) {
    const ScriptStep &st = sc.steps[i];
    char line[128];
    switch (st.op) {
      case StepOp::Tap:
        snprintf(line, sizeof(line), "T %.4f %.4f %u", st.a, st.b,
                 (unsigned)(st.ms ? st.ms : 40)); break;
      case StepOp::DoubleTap:
        snprintf(line, sizeof(line), "D %.4f %.4f %u", st.a, st.b,
                 (unsigned)(st.ms ? st.ms : 90)); break;
      case StepOp::Hold:
        snprintf(line, sizeof(line), "H %.4f %.4f %u", st.a, st.b,
                 (unsigned)(st.ms ? st.ms : 900)); break;
      case StepOp::Swipe:
        snprintf(line, sizeof(line), "S %.4f %.4f %.4f %.4f %u", st.a, st.b, st.c,
                 st.d, (unsigned)(st.ms ? st.ms : 220)); break;
      case StepOp::Curve:
        snprintf(line, sizeof(line), "C %.4f %.4f %.4f %.4f %.4f %.4f %u", st.a,
                 st.b, st.c, st.d, (float)st.e / 255.0f, (float)st.f / 255.0f,
                 (unsigned)(st.ms ? st.ms : 220)); break;
      case StepOp::TwoFinger:
        snprintf(line, sizeof(line), "P %.4f %.4f %.4f %.4f %.4f %u", st.a, st.b,
                 st.c, st.d, (float)st.e / 255.0f,
                 (unsigned)(st.ms ? st.ms : 220)); break;
      case StepOp::Pinch:
        snprintf(line, sizeof(line), "Z %.4f %.4f %.4f %.4f %u", st.a, st.b, st.c,
                 st.d, (unsigned)(st.ms ? st.ms : 220)); break;
      case StepOp::Key:
        snprintf(line, sizeof(line), "K %u", (unsigned)st.e); break;
      case StepOp::Consumer:
        snprintf(line, sizeof(line), "V %u", (unsigned)st.e); break;
      case StepOp::Delay:
        snprintf(line, sizeof(line), "wait %u", (unsigned)st.ms); break;
      case StepOp::Repeat:
        snprintf(line, sizeof(line), "repeat %u", (unsigned)st.repeat); break;
      case StepOp::Goto:
        // ⚠️ 必须原样写回 `goto 标号`,不能写成注释 ——
        //    写成注释的话,App 里"编辑一次"就把 goto 全变成注释了
        //    (实测:带 goto 的流程读回来是一堆 "# (goto ...)" 注释)。
        snprintf(line, sizeof(line), "goto %s", st.note); break;
      // ⚠️⚠️ 这四个**必须**在这里也写上!
      //   上面那个 stepToText() 是给**日志显示**用的,这个是给
      //   **存回文件 / App 编辑**用的 —— 两份是分开的。
      //    当初只给显示那份加了分支,结果:App 读流程拿到"# 未知步骤",
      //    一编辑一保存,waitfor 逻辑就全丢了(实测发现的严重 bug)。
      case StepOp::WaitFor:
        snprintf(line, sizeof(line), "waitfor %s %u", st.note,
                 (unsigned)(st.ms ? st.ms : 15000)); break;
      case StepOp::IfPage:
        snprintf(line, sizeof(line), "ifpage %s", st.note); break;
      case StepOp::Else:
        snprintf(line, sizeof(line), "else"); break;
      case StepOp::EndIf:
        snprintf(line, sizeof(line), "endif"); break;
      // ⚠️ endloop 也必须写在这儿 ——
      //    漏了就会回读成 "# 未知步骤",一编辑一保存**循环结构就毁了**
      //    (实测:repeat 配对被换成一行注释,循环彻底失效)。
      case StepOp::EndLoop:
        snprintf(line, sizeof(line), "endloop"); break;
      case StepOp::Break:
        snprintf(line, sizeof(line), "break"); break;
      case StepOp::Notify:
        snprintf(line, sizeof(line), "notify %s", st.note); break;
      default:
        snprintf(line, sizeof(line), "# 未知步骤");
    }
    trimTrailingZeros(line);
    // 有 goto 指向这一步 → 先输出标号行 `#名字`
    // (标号本身不占步骤,只能靠 goto 的目标反推)
    for (uint8_t g = 0; g < sc.stepCount; g++) {
      if (sc.steps[g].op == StepOp::Goto && sc.steps[g].next == i
          && sc.steps[g].note[0] != '\0') {
        out += "#";
        out += sc.steps[g].note;
        out += "\n";
        break;
      }
    }
    out += line;
    // ⚠️ 备注必须一起输出 —— 否则 App/网页"编辑"时备注会丢
    //    但 waitfor / ifpage 的 note 字段装的是**关键字**,已经在上一行输出了,
    //    再附一遍会变成 `waitfor homepage homepage`(关键字就错了)。
    // ⚠️ 这些指令的 note 已经在上面那行里输出过了,不能再附一遍:
    //      WaitFor/IfPage 是关键字、Notify 是消息、Goto 是标号名。
    //    漏一个就会变成 `waitfor homepage homepage` / `goto 结束 结束`
    //    (这两个坑都实际踩过,而且都是"编辑一次流程就坏")。
    bool noteShown = (st.op == StepOp::WaitFor || st.op == StepOp::IfPage
                      || st.op == StepOp::Notify || st.op == StepOp::Goto);
    if (!noteShown && st.note[0] != '\0') { out += " "; out += st.note; }
    out += "\n";
  }
  return out;
}

static String readBody() {
  if (s_server.hasArg("plain")) return s_server.arg("plain");
  String body;
  WiFiClient c = s_server.client();
  body.reserve(1024);
  uint32_t t0 = millis();
  while (c.available() && body.length() < 8192 && millis() - t0 < 3000) {
    body += (char)c.read();
  }
  return body;
}

static bool parseNorm(const String &tok, float &out) {
  if (!tok.length()) return false;
  String s = tok; s.trim();
  float v;
  if (s.endsWith("%")) {
    v = s.substring(0, s.length() - 1).toFloat() / 100.0f;
  } else {
    v = s.toFloat();
    if (v > 1.0f) v /= (float)TOUCH_LOGICAL_MAX;
  }
  if (v < 0) v = 0;
  if (v > 1) v = 1;
  out = v;
  return true;
}

static bool parseXY(const String &xy, float &x, float &y) {
  int comma = xy.indexOf(',');
  if (comma < 0) return false;
  return parseNorm(xy.substring(0, comma), x) &&
         parseNorm(xy.substring(comma + 1), y);
}

// ---------------------------------------------------------------------------
//  HTTP:页面 / 状态
// ---------------------------------------------------------------------------
static void handleRoot() {
  s_server.send_P(200, "text/html; charset=utf-8", PAGE_HTML);
}

// 前置声明:实现在下面的心跳那一节(handleStatus 要用它)
static String fwUpdateJson();

/**
 * 组装 /api/status 的 JSON。
 *
 * ⚠️ 抽出来是因为**两个地方要用**:
 *    ① WiFi 上的 HTTP 接口    ② BLE 控制通道(板子没网时走蓝牙)
 *    复制一份的话迟早两边不一致。
 */
String buildStatusJson() {
const char *mode = s_net.staConnected() ? "STA"
                   : (s_net.apActive() ? "AP" : "OFF");
String j = "{";
j += "\"mode\":\"" + String(mode) + "\",";
j += "\"fw\":\"" + String(FW_VERSION) + "\",";
j += "\"page\":\"" + String(pageAct()) + "\",";
// 页面文字也带上 —— 光有 Activity 名分不出页面:
// 大麦的「首页」和「我的」是同一个 MainActivity(Fragment 切换)
{
  // ⚠️ 必须用 utf8Truncate —— substring 会从中文**字符中间**切,
  //    产生非法 UTF-8,整个 /api/status 的 JSON 都解析不了(实测踩过)
  char ptBuf[120];
  utf8Truncate(ptBuf, pageText(), sizeof(ptBuf));
  j += "\"pageText\":\"" + String(ptBuf) + "\",";
}
j += "\"mac\":\"" + WiFi.macAddress() + "\",";
// 名字 / 备注(App 有 remark 字段才认为这版固件能改备注)
j += "\"name\":\"" + String(boardBaseName()) + "\",";
j += "\"remark\":\"" + jsonEscape(String(s_remark)) + "\",";
j += "\"remarkPending\":" + String(s_remarkPending ? "true" : "false") + ",";
j += "\"pageAt\":" + String(pageSeenAt()) + ",";
j += "\"fwUpdate\":" + fwUpdateJson() + ",";
j += "\"ip\":\"" + s_net.ip() + "\",";
j += "\"url\":\"" + s_net.url() + "\",";
j += "\"hid\":" + String(Touch.ready() ? "true" : "false") + ",";
j += "\"scriptCount\":" + String(Scripts.count()) + ",";
j += "\"fsUsedKB\":" + String(LittleFS.usedBytes() / 1024) + ",";
j += "\"heap\":" + String(ESP.getFreeHeap()) + ",";
j += "\"uptime\":" + String(millis() / 1000) + ",";
j += "\"run\":{";
j += "\"running\":" + String(s_state.running ? "true" : "false") + ",";
  // ⚠️ 把灯的当前状态也报出来。
  //    坑:黄只亮流程那么久(2~3 秒)、绿 5 秒,用户在看手机就错过了,
  //    然后一口咬定"灯没变"。报出来就能**在 App 上直接看到**,
  //    不用再靠盯板子。
  j += "\"led\":\"" + String(Led::modeName()) + "\",";
j += "\"index\":" + String(s_state.index) + ",";
j += "\"step\":" + String(s_state.step) + ",";
j += "\"totalSteps\":" + String(s_state.totalSteps) + ",";
j += "\"repeatDone\":" + String(s_state.repeatDone) + ",";
j += "\"repeatTotal\":" + String(s_state.repeatTotal) + ",";
j += "\"elapsedMs\":" + String(s_state.elapsedMs) + ",";
j += "\"runCount\":" + String(s_state.runCount) + ",";
j += "\"lastOk\":" + String(s_state.lastOk ? "true" : "false") + ",";
j += "\"lastName\":\"" + jsonEscape(String(s_state.lastName)) + "\",";
j += "\"lastError\":\"" + jsonEscape(String(s_state.lastError)) + "\"},";
j += "\"sched\":{";
j += "\"active\":" + String(s_schedActive ? "true" : "false") + ",";
j += "\"remainMs\":" + String(scheduleRemainMs()) + ",";
j += "\"durationMs\":" + String(s_schedDurationMs) + ",";
j += "\"name\":\"" + jsonEscape(String(s_schedName)) + "\",";
j += "\"intervalMs\":" + String(s_schedIntervalMs);
j += "}}";
  return j;
}

static void handleStatus() {
  s_server.send(200, "application/json", buildStatusJson());
}

// ---------------------------------------------------------------------------
//  HTTP:脚本
// ---------------------------------------------------------------------------
static void handleScriptList() {
  String j = "{\"scripts\":[";
  for (uint8_t i = 0; i < Scripts.count(); i++) {
    const Script *sc = Scripts.at(i);
    if (i) j += ",";
    j += "{\"index\":" + String(i) + ",\"name\":\"" + jsonEscape(sc->name) +
         "\",\"steps\":" + String(sc->stepCount) + ",\"stepText\":[";
    for (uint8_t k = 0; k < sc->stepCount; k++) {
      if (k) j += ",";
      j += "\"" + jsonEscape(stepToText(sc->steps[k])) + "\"";
    }
    j += "]}";
  }
  j += "]}";
  s_server.send(200, "application/json", j);
}

static void handleScriptOne() {
  if (!s_server.hasArg("index")) {
    s_server.send(400, "application/json", "{\"message\":\"缺少 index\"}");
    return;
  }
  const Script *sc = Scripts.at(s_server.arg("index").toInt());
  if (!sc) {
    s_server.send(404, "application/json", "{\"message\":\"没有这个脚本\"}");
    return;
  }
  String j = "{\"name\":\"" + jsonEscape(sc->name) + "\",\"text\":\"" +
             jsonEscape(scriptToText(*sc)) + "\"}";
  s_server.send(200, "application/json", j);
}

static void handleRun() {
  if (!s_server.hasArg("index")) {
    s_server.send(400, "application/json", "{\"message\":\"缺少 index\"}");
    return;
  }
  int idx = s_server.arg("index").toInt();
  const Script *sc = Scripts.at((uint8_t)idx);
  if (!sc) {
    s_server.send(404, "application/json", "{\"message\":\"没有这个脚本\"}");
    return;
  }
  if (!Touch.ready()) {
    s_server.send(409, "application/json",
                  "{\"message\":\"HID 未就绪(手机没连上或没订阅),拒绝执行\"}");
    return;
  }
  if (s_state.running) {
    s_server.send(409, "application/json",
                  "{\"message\":\"已有脚本在执行,请先停止\"}");
    return;
  }

  uint16_t repeat = 1;
  uint32_t interval = 0;
  if (s_server.hasArg("repeat")) {
    int v = s_server.arg("repeat").toInt();
    if (v > 0 && v <= 999) repeat = (uint16_t)v;
  }
  if (s_server.hasArg("interval")) interval = (uint32_t)s_server.arg("interval").toInt();

  requestScriptRun((uint8_t)idx, repeat, interval);
  LOGF("%s Web 请求运行 [%d] %s x%u", TAG, idx, sc->name, (unsigned)repeat);
  s_server.send(200, "application/json",
                "{\"ok\":true,\"message\":\"已排队执行 " + jsonEscape(sc->name) +
                " x" + String(repeat) + "\"}");
}

static void handleSave() {
  if (!s_server.hasArg("name")) {
    s_server.send(400, "application/json", "{\"message\":\"缺少 name\"}");
    return;
  }
  String name = s_server.arg("name");
  String body = readBody();
  if (!body.length()) {
    s_server.send(400, "application/json",
                  "{\"message\":\"请求体为空(需要 Content-Type: text/plain)\"}");
    return;
  }
  char err[80] = {0};
  if (Scripts.saveFromText(name.c_str(), body.c_str(), err, sizeof(err))) {
    LOGF("%s Web 保存脚本 %s", TAG, name.c_str());
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"已保存 " + jsonEscape(name) + "\"}");
  } else {
    s_server.send(400, "application/json",
                  "{\"ok\":false,\"message\":\"保存失败:" + jsonEscape(err) + "\"}");
  }
}

/** 恢复缺失的示例脚本(只补不覆盖) */
static void handleSeed() {
  int n = Scripts.restoreExamples();
  s_server.send(200, "application/json",
                "{\"ok\":true,\"created\":" + String(n)
                + ",\"message\":\"已恢复 " + String(n) + " 个示例流程\"}");
}

// 服务端告知的固件更新(App 会读这个并提示用户)
static bool     s_fwHasUpdate  = false;
static char     s_fwVersion[24] = {0};
static char     s_fwNotes[96]   = {0};
static char     s_fwUrl[128]    = {0};
static char     s_fwSha[72]     = {0};
static uint32_t s_fwSize        = 0;

// ---------------------------------------------------------------------------
//  OTA:网页/接口上传 .bin 固件,写进备用分区后重启切换
// ---------------------------------------------------------------------------
static volatile bool s_otaOk = false;

static void handleOtaUpload() {
  HTTPUpload &up = s_server.upload();
  if (up.status == UPLOAD_FILE_START) {
    s_otaOk = false;
    LOGF("%s OTA 开始:%s", TAG, up.filename.c_str());
    // UPDATE_SIZE_UNKNOWN:让库自己按镜像头判断大小
    if (!Update.begin(UPDATE_SIZE_UNKNOWN)) {
      LOGF("%s OTA begin 失败: %s", TAG, Update.errorString());
    }
  } else if (up.status == UPLOAD_FILE_WRITE) {
    if (Update.write(up.buf, up.currentSize) != up.currentSize) {
      LOGF("%s OTA 写入失败: %s", TAG, Update.errorString());
    }
  } else if (up.status == UPLOAD_FILE_END) {
    if (Update.end(true)) {
      s_otaOk = true;
      LOGF("%s OTA 完成:%u 字节,准备重启", TAG, (unsigned)up.totalSize);
    } else {
      LOGF("%s OTA 结束失败: %s", TAG, Update.errorString());
    }
  } else if (up.status == UPLOAD_FILE_ABORTED) {
    Update.abort();
    LOGF("%s OTA 被中止", TAG);
  }
}

static void hbJsonEscape(String &out, const String &in);   // 定义在心跳那一节

/** 32 字节摘要 → 64 位小写十六进制 */
static void shaHex(const uint8_t *d, char *out) {
  static const char *H = "0123456789abcdef";
  for (int i = 0; i < 32; i++) { out[i * 2] = H[d[i] >> 4]; out[i * 2 + 1] = H[d[i] & 15]; }
  out[64] = 0;
}

/** 流程在跑 / 排着队 / 频率循环开着 ⇒ 不能升级(升级要重启,会打断) */
static bool otaBusyReason(String &why) {
  if (Scripts.running() || s_state.running) { why = "流程执行中"; return true; }
  if (s_hasRequest) { why = "有流程排队待执行"; return true; }
  if (s_schedActive) { why = "频率循环开着"; return true; }
  return false;
}

/**
 * 从控制中心下载固件写进备用分区,**核对大小和 sha256 都对上**才切换启动分区。
 *
 * 边下边写、边算 sha256(固件 1.2MB,RAM 只有 ~160KB 空闲,不能整包放内存)。
 * 校验不对就 Update.abort():备用分区作废,启动分区不变,板子继续跑旧固件。
 *
 * @param url     下载地址(服务端给的,带 X-Token 请求)
 * @param wantSha 期望的 sha256(64 位十六进制);空 = 老数据没有,只核对大小
 * @param wantSize 期望字节数;0 = 不核对
 * @param err     失败原因(给 App / 服务端看)
 * @return true = 已写入并切好启动分区(调用方负责回复后重启)
 */
static bool otaDownloadAndFlash(const char *url, const char *wantSha, uint32_t wantSize, String &err) {
  WiFiClient client;
  HTTPClient http;
  http.setConnectTimeout(8000);
  http.setTimeout(20000);
  // 跟着重定向走(服务器可能 301/307)
  http.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);
  if (!http.begin(client, String(url))) { err = "连不上控制中心"; return false; }
  http.addHeader("X-Token", SERVER_TOKEN);
  int code = http.GET();
  if (code != 200) {
    http.end();
    err = String("下载失败 HTTP ") + code;
    LOGF("%s %s", TAG, err.c_str());
    return false;
  }

  int len = http.getSize();
  if (wantSize && len > 0 && (uint32_t)len != wantSize) {
    http.end();
    err = String("文件大小不对:服务器说 ") + wantSize + ",实际 " + len;
    LOGF("%s %s", TAG, err.c_str());
    return false;
  }
  if (!Update.begin(len > 0 ? (size_t)len : UPDATE_SIZE_UNKNOWN)) {
    http.end();
    err = String("准备写入失败:") + Update.errorString();
    LOGF("%s %s", TAG, err.c_str());
    return false;
  }

  mbedtls_sha256_context sha;
  mbedtls_sha256_init(&sha);
  mbedtls_sha256_starts_ret(&sha, 0);
  static uint8_t buf[4096];
  WiFiClient *st = http.getStreamPtr();
  size_t got = 0;
  uint32_t lastData = millis();
  while (http.connected() && (len <= 0 || got < (size_t)len)) {
    size_t n = st->available();
    if (!n) {
      if (millis() - lastData > 20000) break;      // 20 秒没数据 ⇒ 当断了
      delay(2);
      continue;
    }
    n = st->readBytes(buf, n > sizeof(buf) ? sizeof(buf) : n);
    if (!n) continue;
    lastData = millis();
    mbedtls_sha256_update_ret(&sha, buf, n);
    if (Update.write(buf, n) != n) {
      err = String("写入失败:") + Update.errorString();
      break;
    }
    got += n;
  }
  http.end();
  uint8_t dig[32];
  mbedtls_sha256_finish_ret(&sha, dig);
  mbedtls_sha256_free(&sha);
  char hex[65];
  shaHex(dig, hex);

  if (!err.length() && len > 0 && got != (size_t)len) {
    err = String("下载不完整:") + got + " / " + len + " 字节";
  }
  if (!err.length() && wantSize && got != wantSize) {
    err = String("文件大小不对:应为 ") + wantSize + ",收到 " + got;
  }
  if (!err.length() && wantSha && wantSha[0] && strcasecmp(hex, wantSha) != 0) {
    err = String("sha256 不对,固件可能损坏(收到 ") + String(hex).substring(0, 12) + "…)";
  }
  if (err.length()) {
    Update.abort();
    LOGF("%s 升级放弃:%s(旧固件不受影响)", TAG, err.c_str());
    return false;
  }
  if (!Update.end(true)) {
    err = String("校验镜像失败:") + Update.errorString();
    LOGF("%s %s", TAG, err.c_str());
    return false;
  }
  LOGF("%s 固件写入完成:%u 字节,sha256 %s", TAG, (unsigned)got,
       (wantSha && wantSha[0]) ? "已核对" : "(服务器没给,未核对)");
  return true;
}

/**
 * 从**控制中心**下载固件并升级(App 点「开始升级」调这个)。
 *
 * 板子自己去服务器拉 .bin(带宽好、不占 App),写完重启。
 * 地址 / sha256 来自上一次心跳里服务端说的「有新固件」。
 */
static void handleOtaFromServer() {
  if (!s_fwHasUpdate || s_fwUrl[0] == 0) {
    s_server.send(400, "application/json",
                  "{\"ok\":false,\"message\":\"没有待升级的固件\"}");
    return;
  }
  String why;
  if (otaBusyReason(why)) {
    s_server.send(409, "application/json",
                  "{\"ok\":false,\"message\":\"" + why + ",现在不能升级\"}");
    return;
  }
  LOGF("%s 开始从控制中心下载固件:%s", TAG, s_fwVersion);
  String err;
  if (!otaDownloadAndFlash(s_fwUrl, s_fwSha, s_fwSize, err)) {
    String e; hbJsonEscape(e, err);
    s_server.send(500, "application/json", "{\"ok\":false,\"message\":\"" + e + "\"}");
    return;
  }
  s_server.send(200, "application/json",
                "{\"ok\":true,\"message\":\"固件已写入,正在重启...\"}");
  delay(800);
  ESP.restart();
}

static void handleOtaDone() {
  if (s_otaOk) {
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"固件已写入,正在重启...\"}");
    delay(600);
    ESP.restart();
  } else {
    s_server.send(500, "application/json",
                  String("{\"ok\":false,\"message\":\"OTA 失败:") +
                      Update.errorString() + "\"}");
  }
}

/**
 * App 把"当前页面"推给板子,供脚本的 waitfor / ifpage 使用。
 *
 * 为什么不反过来让板子问 App:板子是 HID 外设,手机上的 App 没有固定地址
 * (DHCP 会变),板子主动找它更麻烦。App 推过来最简单。
 *
 * 只在**流程执行期间**推送(1 秒几次也不费什么),空闲时不推。
 */
static uint32_t s_pageLogAt = 0;
/** GET /api/page 返回当前页面详情(诊断用) */
static void handlePageGet() {
  String pt = String(pageText());
  String out = "{\"ok\":true,\"act\":\"" + String(pageAct()) + "\",\"at\":" +
               String((unsigned long)pageSeenAt()) + ",\"text\":\"" + pt + "\"}";
  s_server.send(200, "application/json", out);
}

static void handlePage() {
  String act  = s_server.arg("act");
  String text = s_server.arg("text");
  setPageInfo(act.c_str(), text.c_str());
  // ⚠️ 这里**故意不记日志**。
  //    页面推送每 700ms 一次,就算加了 5 秒限流,长时间下来
  //    也会把日志缓冲刷满 —— 用户想看**流程执行日志**却全是这行
  //    (实测:80 行里 70 多行都是"收到页面推送:XXX")。
  //    当前页面在悬浮窗和 /api/page 都能看,不需要再往日志里写。
  s_server.send(200, "application/json", "{\"ok\":true}");
}

static void handleDelete() {
  if (!s_server.hasArg("name")) {
    s_server.send(400, "application/json", "{\"message\":\"缺少 name\"}");
    return;
  }
  String name = s_server.arg("name");
  if (Scripts.isRunningFlow(name.c_str())) {
    s_server.send(409, "application/json",
                  "{\"ok\":false,\"message\":\"「" + jsonEscape(name) + "」正在执行,先停止再删除\"}");
    return;
  }
  if (Scripts.remove(name.c_str())) {
    LOGF("%s Web 删除脚本 %s", TAG, name.c_str());
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"已删除 " + jsonEscape(name) + "\"}");
  } else {
    s_server.send(404, "application/json", "{\"message\":\"删除失败(不存在?)\"}");
  }
}

static void handleStop() {
  requestScriptStop();
  requestScheduleStop();
  s_server.send(200, "application/json",
                "{\"ok\":true,\"message\":\"已请求停止(当前步骤结束后生效)\"}");
}

// ---------------------------------------------------------------------------
//  HTTP:手势 / 设备控制
// ---------------------------------------------------------------------------
static void handleGesture() {
  if (!Touch.ready()) {
    s_server.send(409, "application/json", "{\"message\":\"HID 未就绪\"}");
    return;
  }
  String op = s_server.arg("op");
  float x = 0, y = 0, cx = 0, cy = 0;
  uint32_t ms = s_server.hasArg("ms") ? s_server.arg("ms").toInt() : 0;

  if (s_server.hasArg("xy")) {
    if (!parseXY(s_server.arg("xy"), x, y)) {
      s_server.send(400, "application/json",
                    "{\"message\":\"xy 格式应为 0.5,0.5 或 50%,50%\"}");
      return;
    }
  } else {
    if (s_server.hasArg("a")) parseNorm(s_server.arg("a"), x);
    if (s_server.hasArg("b")) parseNorm(s_server.arg("b"), y);
    if (s_server.hasArg("c")) parseNorm(s_server.arg("c"), cx);
    if (s_server.hasArg("d")) parseNorm(s_server.arg("d"), cy);
  }

  char msg[96];
  if (op == "tap") {
    Touch.tap(x, y, ms ? ms : 60);
    snprintf(msg, sizeof(msg), "已点击 %.3f,%.3f", x, y);
  } else if (op == "double") {
    Touch.doubleTap(x, y, ms ? ms : 90);
    snprintf(msg, sizeof(msg), "已双击 %.3f,%.3f", x, y);
  } else if (op == "long") {
    Touch.longPress(x, y, ms ? ms : 900);
    snprintf(msg, sizeof(msg), "已长按 %.3f,%.3f", x, y);
  } else if (op == "swipe") {
    Touch.swipe(x, y, cx, cy, ms ? ms : 300);
    snprintf(msg, sizeof(msg), "已滑动 %.2f,%.2f -> %.2f,%.2f", x, y, cx, cy);
  } else if (op == "curve") {
    Touch.curveSwipe(x, y, cx, cy, cx, cy, ms ? ms : 300);
    snprintf(msg, sizeof(msg), "已曲线滑动");
  } else if (op == "pinch") {
    Touch.pinch(x, y, cx, cy, ms ? ms : 300);
    snprintf(msg, sizeof(msg), "已捏合/张开");
  } else if (op == "releaseall") {
    Touch.releaseAll();
    snprintf(msg, sizeof(msg), "已抬起全部触点");
  } else {
    s_server.send(400, "application/json", "{\"message\":\"未知手势 op\"}");
    return;
  }

  LOGF("%s Web 手势: %s", TAG, msg);
  s_server.send(200, "application/json",
                String("{\"ok\":true,\"message\":\"") + msg + "\"}");
}

// 发单个按键:kind=key(键盘 usage) 或 consumer(多媒体 usage)
static void handleKey() {
#if !HID_INCLUDE_KEYBOARD && !HID_INCLUDE_CONSUMER
  s_server.send(409, "application/json",
                "{\"message\":\"键盘/多媒体接口已禁用(会触发手机的外接键盘弹窗)"
                ",需要时改 hid_report_map.h 里的 HID_INCLUDE_* 重新编译\"}");
  return;
#endif
  if (!Touch.ready()) {
    s_server.send(409, "application/json", "{\"message\":\"HID 未就绪\"}");
    return;
  }
  String kind = s_server.arg("kind");
  int usage = s_server.hasArg("usage") ? s_server.arg("usage").toInt() : 0;
  char msg[80];
  if (kind == "consumer") {
    Touch.consumerTap((uint16_t)usage);
    snprintf(msg, sizeof(msg), "已发送多媒体键 0x%X", usage);
  } else {
    Touch.keyTap((uint8_t)usage);
    snprintf(msg, sizeof(msg), "已发送按键 usage=%d", usage);
  }
  LOGF("%s Web 按键: %s", TAG, msg);
  s_server.send(200, "application/json",
                String("{\"ok\":true,\"message\":\"") + msg + "\"}");
}

static void handleControl() {
  String what = s_server.arg("do");
  const char *msg = nullptr;

  if (what == "home") {
    Touch.tap(0.50f, 0.963f, 60);
    msg = "已回桌面(点方块键)";
  } else if (what == "back") {
    Touch.tap(0.78f, 0.963f, 60);
    msg = "已返回(点左箭头)";
  } else if (what == "recents") {
    Touch.tap(0.22f, 0.963f, 60);
    msg = "已打开最近任务";
  } else if (what == "wake") {
    Touch.tap(0.50f, 0.50f, 40);
    msg = "已发送一次点击(用于唤醒)";
  } else if (what == "power") {
    Touch.consumerTap(0x30);
    msg = "已发送电源键";
  } else if (what == "volup") {
    Touch.consumerTap(0xE9);
    msg = "已发送音量+";
  } else if (what == "voldown") {
    Touch.consumerTap(0xEA);
    msg = "已发送音量-";
  } else if (what == "mute") {
    Touch.consumerTap(0xE2);
    msg = "已发送静音";
  } else if (what == "releaseall") {
    Touch.releaseAll();
    msg = "已抬起全部触点";
  } else {
    s_server.send(400, "application/json", "{\"message\":\"未知控制项\"}");
    return;
  }

  LOGF("%s Web 控制: %s", TAG, msg);
  s_server.send(200, "application/json",
                String("{\"ok\":true,\"message\":\"") + msg + "\"}");
}

// ---------------------------------------------------------------------------
//  HTTP:调度 / 系统 / 日志
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
//  P8:配网 —— 把 WiFi 凭据存进 NVS,并支持失败自动回退
// ---------------------------------------------------------------------------
static String s_wifiSsid, s_wifiPass;

static void loadWifiFromPrefs() {
  Preferences p;
  if (!p.begin("wifi", true)) return;
  s_wifiSsid = p.getString("ssid", "");
  s_wifiPass = p.getString("pass", "");
  p.end();
}

static void clearWifiPrefs() {
  Preferences p;
  if (!p.begin("wifi", false)) return;
  p.clear();
  p.end();
}

static void saveWifiToPrefs(const String& ssid, const String& pass) {
  Preferences p;
  if (!p.begin("wifi", false)) return;
  p.putString("ssid", ssid);
  p.putString("pass", pass);
  p.end();
}

// ---------------------------------------------------------------------------
//  板子名字 + 备注(声明和规则见 net_manager.h)
//
//  备注以服务器为准:
//    · 板子网页改 → 先问服务器查重;服务器连不上就先存板子上、标「待同步」,
//      下次心跳带上去,服务器发现重名就不采用、回复里给回它那份
//    · 后台 / App 改 → 服务器查过重,由心跳回复(或 App 直接)下发,板子照写
//    · 心跳回复里总带服务器那份备注,和板子不一样就以服务器为准
// ---------------------------------------------------------------------------

const char *boardBaseName() {
  if (!s_boardName[0]) {
    uint8_t m[6] = {0};
    esp_read_mac(m, ESP_MAC_WIFI_STA);      // 和 WiFi.macAddress() 是同一个 MAC
    snprintf(s_boardName, sizeof(s_boardName), "%s_%02X%02X", BOARD_NAME_PREFIX, m[4], m[5]);
  }
  return s_boardName;
}

static void loadRemarkFromPrefs() {
  Preferences p;
  if (!p.begin("board", true)) return;      // 没写过时命名空间不存在,正常
  String r = p.getString("remark", "");
  strncpy(s_remark, r.c_str(), sizeof(s_remark) - 1);
  s_remarkPending = p.getBool("rmPend", false);
  p.end();
}

static void saveRemark(const String &r, bool pending) {
  strncpy(s_remark, r.c_str(), sizeof(s_remark) - 1);
  s_remark[sizeof(s_remark) - 1] = 0;
  s_remarkPending = pending;
  Preferences p;
  if (!p.begin("board", false)) { LOGF("%s 备注写 NVS 失败", TAG); return; }
  p.putString("remark", s_remark);
  p.putBool("rmPend", pending);
  p.end();
}

/** 板子侧的粗校验(细规则 —— 字数、查重 —— 以服务器为准) */
static bool remarkValid(const String &r, String &why) {
  if (r.length() > REMARK_MAX_BYTES) { why = "备注太长(最多 20 个字)"; return false; }
  for (size_t i = 0; i < r.length(); i++) {
    char c = r[i];
    if (c == '"' || c == '\\' || (uint8_t)c < 0x20) {
      why = "备注不能含引号、反斜杠或换行";
      return false;
    }
  }
  return true;
}

bool remarkApplyFromServer(const String &remark, String &why) {
  String r = remark;
  r.trim();
  if (!remarkValid(r, why)) return false;
  if (r != s_remark || s_remarkPending) {
    LOGF("%s 备注更新:「%s」→「%s」(服务器已确认)", TAG, s_remark, r.c_str());
    saveRemark(r, false);
  }
  return true;
}

/**
 * 让服务器查重并保存备注。
 * @return HTTP 状态码(200 = 采用;409 = 重名;400 = 不合法);负数 = 连不上
 */
static int remarkAskServer(const String &r, String &msg) {
  HTTPClient http;
  http.setConnectTimeout(4000);
  http.setTimeout(6000);
  if (!http.begin(String(SERVER_URL) + "/api/v1/board/remark")) return -1;
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Token", SERVER_TOKEN);
  String body = "{\"mac\":\"" + WiFi.macAddress() + "\",\"remark\":\"" + jsonEscape(r) + "\"}";
  int code = http.POST(body);
  if (code > 0) {
    String resp = http.getString();
    int i = resp.indexOf("\"message\":\"");
    if (i >= 0) {
      int e = resp.indexOf('"', i + 11);
      if (e > i) msg = resp.substring(i + 11, e);
    }
  }
  http.end();
  return code;
}

/**
 * 改备注:POST /api/remark?remark=xxx[&from=server]
 *   from=server:App 已经让服务器查过重了,直接写
 *   不带:板子网页改的 —— 先问服务器;连不上就先存、标待同步
 */
static void handleRemark() {
  if (!s_server.hasArg("remark")) {
    s_server.send(400, "application/json", "{\"ok\":false,\"message\":\"缺少 remark\"}");
    return;
  }
  String r = s_server.arg("remark");
  r.trim();
  String why;
  if (!remarkValid(r, why)) {
    s_server.send(400, "application/json", "{\"ok\":false,\"message\":\"" + why + "\"}");
    return;
  }
  if (s_server.arg("from") == "server") {
    remarkApplyFromServer(r, why);
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"remark\":\"" + jsonEscape(String(s_remark)) + "\"}");
    return;
  }
  if (WiFi.status() == WL_CONNECTED) {
    String msg;
    int code = remarkAskServer(r, msg);
    if (code == 200) {
      saveRemark(r, false);
      LOGF("%s 备注已改为「%s」(服务器已同步)", TAG, s_remark);
      s_server.send(200, "application/json",
                    "{\"ok\":true,\"remark\":\"" + jsonEscape(String(s_remark)) +
                    "\",\"message\":\"已保存,服务器已同步\"}");
      return;
    }
    if (code == 409 || code == 400) {
      LOGF("%s 备注「%s」被服务器拒绝:%s", TAG, r.c_str(), msg.c_str());
      s_server.send(code, "application/json",
                    "{\"ok\":false,\"message\":\"" + jsonEscape(msg.length() ? msg : String("服务器拒绝")) + "\"}");
      return;
    }
    LOGF("%s 改备注:服务器没回应(%d),先存板子上", TAG, code);
  }
  saveRemark(r, true);
  s_server.send(200, "application/json",
                "{\"ok\":true,\"pending\":true,\"remark\":\"" + jsonEscape(String(s_remark)) +
                "\",\"message\":\"服务器暂时连不上,已先存在板子上,下次心跳再同步(重名会被服务器拒绝)\"}");
}

/**
 * 配网接口:POST /api/wifi?ssid=xxx&pass=yyy
 *
 * 防失联设计:
 *   先保存新凭据 → 尝试连接(最多 20 秒)→ 连不上就**回退到旧凭据**并写回 NVS,
 *   同时在响应里说明失败。这样配错了也不会把板子变成砖头。
 */
/**
 * 修复蓝牙连接:POST /api/ble?op=reset
 * 用于手机侧 HOGP 卡住(蓝牙显示已连接,但系统里没有输入设备)时强制重连。
 */
static void handleBle() {
  String op = s_server.hasArg("op") ? s_server.arg("op") : "reset";

  // op=info:报告连接/绑定情况,用于回答"能连几个手机"
  if (op == "info") {
    String j = hidTransport().linkInfo();
    if (j.length() < 3) j = "{\"message\":\"当前传输层不支持诊断(USB 模式)\"}";
    s_server.send(200, "application/json", j);
    return;
  }

  if (op == "reset") {
    hidTransport().resetLink();
    LOGF("%s 蓝牙连接已强制重置", TAG);
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"已强制重置蓝牙连接,请稍等几秒\"}");
  } else {
    s_server.send(400, "application/json", "{\"message\":\"未知 op\"}");
  }
}

static void handleWifi() {
  if (!s_server.hasArg("ssid")) {
    // 只查询状态
    String j = "{\"ok\":true,\"ssid\":\"" + s_wifiSsid
             + "\",\"connected\":" + String(WiFi.status() == WL_CONNECTED ? "true" : "false")
             + ",\"ip\":\"" + WiFi.localIP().toString() + "\"}";
    s_server.send(200, "application/json", j);
    return;
  }

  String ssid = s_server.arg("ssid");
  String pass = s_server.hasArg("pass") ? s_server.arg("pass") : "";

  // ⚠️ 关键设计:**先试连,成功了才写 NVS**。
  //    之前是"先存再试",首次配网失败时错误凭据会留在 NVS 里,
  //    板子一重启就读到错的 SSID → 彻底失联,只能重新烧录。实测踩过。
  loadWifiFromPrefs();
  String oldSsid = s_wifiSsid, oldPass = s_wifiPass;
  String fbSsid = oldSsid.length() ? oldSsid : String(WIFI_SSID);
  String fbPass = oldSsid.length() ? oldPass : String(WIFI_PASS);

  LOGF("%s 配网:尝试连接 %s", TAG, ssid.c_str());
  WiFi.disconnect();
  delay(200);
  WiFi.begin(ssid.c_str(), pass.c_str());
  WiFi.setSleep(true);              // WiFi+BLE 共存必须允许省电

  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000) delay(300);

  if (WiFi.status() == WL_CONNECTED) {
    // 成功 → 这时才落盘
    saveWifiToPrefs(ssid, pass);
    s_wifiSsid = ssid;
    s_wifiPass = pass;
    String m = "配网成功:" + WiFi.localIP().toString();
    LOGF("%s %s", TAG, m.c_str());
    s_server.send(200, "application/json",
                  String("{\"ok\":true,\"message\":\"") + m + "\",\"ip\":\""
                  + WiFi.localIP().toString() + "\"}");
    return;
  }

  // 失败 → 回退到"上一个能用的网络",NVS 保持原样(不动它)
  LOGF("%s 配网失败,回退到 %s", TAG, fbSsid.c_str());
  WiFi.disconnect();
  delay(200);
  WiFi.begin(fbSsid.c_str(), fbPass.c_str());
  WiFi.setSleep(true);
  uint32_t t1 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t1 < 15000) delay(300);

  bool back = (WiFi.status() == WL_CONNECTED);
  LOGF("%s 回退结果: %s", TAG, back ? "已恢复" : "仍未连上");
  String m = back
      ? "配网失败(密码错或信号弱),已回退到原网络"
      : "配网失败,且回退也没连上(请检查默认凭据)";
  s_server.send(back ? 500 : 500, "application/json",
                String("{\"ok\":false,\"message\":\"") + m + "\"}");
}

static void handleSchedule() {
  if (!s_server.hasArg("index")) {
    s_server.send(400, "application/json", "{\"message\":\"缺少 index\"}");
    return;
  }
  int idx = s_server.arg("index").toInt();
  // 频率单位毫秒(旧参数 sec 仍兼容:乘 1000)
  uint32_t ms = 100;
  if (s_server.hasArg("ms")) {
    ms = (uint32_t)s_server.arg("ms").toInt();
  } else if (s_server.hasArg("sec")) {
    ms = (uint32_t)s_server.arg("sec").toInt() * 1000UL;
  }
  if (ms < 10) ms = 10;          // 下限 10ms
  // 连续执行时长:优先 durMs,其次 dur(秒);0/缺省 = 不限
  uint32_t dur = 0;
  if (s_server.hasArg("durMs")) {
    dur = (uint32_t)s_server.arg("durMs").toInt();
  } else if (s_server.hasArg("dur")) {
    dur = (uint32_t)s_server.arg("dur").toInt() * 1000UL;
  }
  requestSchedule(idx, ms, dur);
  String m = "已开始频率执行:每 " + String(ms) + " 毫秒一次";
  if (dur) m += ",连续 " + String(dur / 1000) + " 秒后自动停";
  LOGF("%s %s", TAG, m.c_str());
  s_server.send(200, "application/json",
                String("{\"ok\":true,\"message\":\"") + m + "\"}");
}

static void handleScheduleStop() {
  requestScheduleStop();
  s_server.send(200, "application/json", "{\"ok\":true,\"message\":\"已停止定时\"}");
}

static void handleSystem() {
  String op = s_server.arg("op");
  if (op == "reboot") {
    LOGF("%s Web 请求重启", TAG);
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"板子即将重启\"}");
    delay(300);
    ESP.restart();
  } else if (op == "forgetwifi") {
    // 忘掉当前 WiFi → 重启后会走热点模式(用于换网络/搬家)
    clearWifiPrefs();
    LOGF("%s 已清除 WiFi 配置,重启进入热点模式", TAG);
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"已忘记 WiFi,重启后开热点\"}");
    delay(500);
    ESP.restart();
  } else if (op == "factory") {
    if (Scripts.running()) {
      s_server.send(409, "application/json",
                    "{\"ok\":false,\"message\":\"有流程正在执行,先停止再恢复出厂\"}");
      return;
    }
    int n = Scripts.removeAll();
    LOGF("%s Web 请求恢复出厂,清空 %d 个脚本", TAG, n);
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"已清空 " + String(n) +
                  " 个脚本,重启后写回示例\"}");
  } else {
    s_server.send(400, "application/json", "{\"message\":\"未知系统操作\"}");
  }
}

/**
 * POST /api/log —— 让 App 往板子日志里塞一行。
 *
 * 为什么需要:定时触发、息屏这类事发生在"人不在 App 里"的时候,
 * 板子日志是**唯一**能事后查的地方(实测:定时没触发时无从下手)。
 */
/**
 * 往控制中心报一个流程事件。
 *
 * 板子在内网,人在外面 —— 只有服务器能中转。
 * 失败**不重试、不报错**,只是记一行日志:抢票流程不能因为网络问题卡住。
 */
// ---------------------------------------------------------------- BLE 控制通道用的入口
//  ⚠️ 这几个都是给 ble_command.cpp 调的。
//    BLE 那边**不能**直接用 s_server(那是 HTTP 的),
//    所以这里开几个"和 HTTP 接口等价"的函数。

void NetManager::requestRun(uint8_t index) {
  const Script *sc = Scripts.at(index);
  if (!sc) return;
  requestScriptRun(index, 1, 0);
  LOGF("%s [BLE] 请求运行 [%u] %s", TAG, (unsigned)index, sc->name);
}

// LogBuffer::forEach 收的是**函数指针**(不是 std::function),
// 所以不能用带捕获的 lambda —— 用 context 传字符串回去。
static void logsAppend(const char *line, void *ctx) {
  String *j = (String *)ctx;
  if (j->length() > 10 && (*j)[j->length() - 1] != '[') *j += ",";
  *j += "\"" + jsonEscape(String(line)) + "\"";
}

String NetManager::logsJsonTail(int n) {
  if (n <= 0) n = 8;
  uint16_t total = Log.size();
  int from = (int)total - n;
  if (from < 0) from = 0;
  String lines = "[";
  for (int i = from; i < (int)total; i++) {
    const char *l = Log.at((uint16_t)i);
    if (!l) continue;
    if (lines.length() > 1) lines += ",";
    lines += "\"" + jsonEscape(String(l)) + "\"";
  }
  lines += "]";
  return "{\"lines\":" + lines + ",\"total\":" + String((int)total) + "}";
}

String NetManager::logsJson() {
  String lines = "[";
  Log.forEach(logsAppend, &lines);
  lines += "]";
  return "{\"lines\":" + lines + ",\"total\":" + String((int)Log.size()) + "}";
}

void NetManager::stopSchedulePublic() {
  if (s_schedActive) {
    s_schedActive = false;
    LOGF("%s [BLE] 已停止定时/频率循环", TAG);
  }
}

void NetManager::clearLogs() {
  Log.clear();
  LOGLN("日志已清空");
}

String NetManager::scriptText(uint8_t index) {
  const Script *sc = Scripts.at(index);
  if (!sc) return "";
  return scriptToText(*sc);
}

bool NetManager::isRunning() const { return s_state.running; }

/** /api/status 的 JSON(HTTP 和 BLE 控制通道共用同一份) */
String NetManager::statusJson() { return buildStatusJson(); }

bool NetManager::reportFlowEvent(const char *flowName, const char *message) {
  if (!staConnected()) { LOGF("%s 通知服务器:没连上 WiFi", TAG); return false; }
  if (!message || !*message) return false;

  HTTPClient http;
  http.setConnectTimeout(4000);
  http.setTimeout(6000);
  String url = String(SERVER_URL) + "/api/v1/board/event";
  if (!http.begin(url)) { LOGF("%s 通知服务器:连不上", TAG); return false; }
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Token", SERVER_TOKEN);

  String body = "{";
  body += "\"mac\":\"" + WiFi.macAddress() + "\",";
  body += "\"type\":\"flow_event\",";
  body += "\"flow\":\"" + jsonEscape(String(flowName ? flowName : "")) + "\",";
  body += "\"message\":\"" + jsonEscape(String(message)) + "\"";
  body += "}";

  int code = http.POST(body);
  http.end();
  if (code == 200) {
    LOGF("%s 通知服务器成功:%s", TAG, message);
    return true;
  }
  LOGF("%s 通知服务器失败(HTTP %d):%s", TAG, code, message);
  return false;
}

static void handleLogAppend() {
  String body = readBody();
  body.trim();
  if (body.length() == 0) {
    s_server.send(400, "application/json", "{\"message\":\"日志内容为空\"}");
    return;
  }
  if (body.length() > 160) body = body.substring(0, 160);
  LOGF("[app] %s", body.c_str());
  s_server.send(200, "application/json", "{\"ok\":true}");
}

static void handleLogs() {
  String j = "{\"lines\":[";
  uint16_t n = Log.size();
  for (uint16_t i = 0; i < n; i++) {
    if (i) j += ",";
    j += "\"" + jsonEscape(String(Log.at(i))) + "\"";
  }
  j += "],\"total\":" + String(n) + "}";
  s_server.send(200, "application/json", j);
}

/**
 * /api/led?r=&g=&b=&ms=  —— 点灯(用来在几块同名板子里认出这一块)
 * 不传参数 = 进「找板子」模式(快-快-慢 + 绿色常亮)
 */
static void handleLed() {
  // ⚠️⚠️ **运行中禁止远程点灯**(需求明确要求)。
  //     理由:点灯会干扰现场排查 —— 你正看着灯判断状态,
  //     后台/App 一点就把颜色改掉了。
  //     注意:串口那条**不拦** —— 那是本地调试,现场就要用。
  if (s_state.running) {
    s_server.send(409, "application/json",
                  "{\"ok\":false,\"message\":\"板子正在执行流程,已禁止远程改灯"
                  "(等它跑完再试;串口 LED 命令不受限)\"}");
    return;
  }
  const String mode = s_server.hasArg("mode") ? s_server.arg("mode") : String("");
  if (mode.equalsIgnoreCase("test")) {
    Led::colorTest();
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"四色自检已启动:红→黄→绿→灭,各 1.5 秒\"}");
    return;
  }
  if (mode.equalsIgnoreCase("auto")) {
    Led::autoMode();
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"已回到自动(未连接蓝/已连接绿/执行中黄)\"}");
    return;
  }
  if (mode.equalsIgnoreCase("off")) {
    Led::off();
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"已关灯(保持熄灭,不会被状态色点亮)\"}");
    return;
  }
  if (!s_server.hasArg("r") && !s_server.hasArg("g") && !s_server.hasArg("b")) {
    Led::identify();
    s_server.send(200, "application/json",
                  "{\"ok\":true,\"message\":\"已进入找板子模式(灯闪 60 秒)\"}");
    return;
  }
  int r = s_server.hasArg("r") ? s_server.arg("r").toInt() : 0;
  int g = s_server.hasArg("g") ? s_server.arg("g").toInt() : 0;
  int b = s_server.hasArg("b") ? s_server.arg("b").toInt() : 0;
  uint32_t ms = s_server.hasArg("ms") ? (uint32_t)s_server.arg("ms").toInt() : 0;
  Led::set((uint8_t)constrain(r, 0, 255), (uint8_t)constrain(g, 0, 255),
           (uint8_t)constrain(b, 0, 255), ms);
  s_server.send(200, "application/json",
                "{\"ok\":true,\"message\":\"LED 已设置为 " + String(r) + "," +
                String(g) + "," + String(b) + "\"}");
}

static void handleClearLogs() {
  Log.clear();
  Log.line("[web] 日志已清空");
  s_server.send(200, "application/json", "{\"ok\":true}");
}

static void handleNotFound() {
  s_server.send(404, "application/json", "{\"message\":\"未知接口\"}");
}

// ---------------------------------------------------------------------------
//  NetManager
// ---------------------------------------------------------------------------
bool NetManager::begin() {
  loadRemarkFromPrefs();
  LOGF("%s 板子名字:%s,备注:%s%s", TAG, boardBaseName(), s_remark[0] ? s_remark : "(无)",
       s_remarkPending ? "(待同步)" : "");
  // 优先用 NVS 里保存的凭据(手机配网写入),没有则用编译期默认值
  loadWifiFromPrefs();
  const char* ssid = s_wifiSsid.length() ? s_wifiSsid.c_str() : WIFI_SSID;
  const char* pass = s_wifiSsid.length() ? s_wifiPass.c_str() : WIFI_PASS;
  LOGF("%s 连接 WiFi: %s", TAG, ssid);

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(true);   // WiFi+BLE 共存必须允许省电,否则重启
  WiFi.begin(ssid, pass);

  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 12000) {
    delay(250);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    _ap = false;
    LOGF("%s 已连接,IP: %s", TAG, WiFi.localIP().toString().c_str());
  } else {
    LOGF("%s 连接失败(%d),回退到热点模式", TAG, (int)WiFi.status());
    WiFi.mode(WIFI_AP);
    WiFi.setSleep(true);
    // 热点名就是板子名字(和蓝牙名一致,几块板子一起开热点也分得清)
    const char *apSsid = boardBaseName();
    if (!WiFi.softAP(apSsid, AP_PASS)) {
      LOGF("%s 热点也起不来,Web 管理不可用", TAG);
      return false;
    }
    _ap = true;
    LOGF("%s 热点已开: %s / %s", TAG, apSsid, AP_PASS);
    LOGF("%s 访问地址: http://%s/", TAG, WiFi.softAPIP().toString().c_str());
  }

  s_server.on("/", HTTP_GET, handleRoot);
  s_server.on("/api/status", HTTP_GET, handleStatus);
  s_server.on("/api/scripts", HTTP_GET, handleScriptList);
  s_server.on("/api/script", HTTP_GET, handleScriptOne);
  s_server.on("/api/run", HTTP_POST, handleRun);
  s_server.on("/api/save", HTTP_POST, handleSave);
  s_server.on("/api/delete", HTTP_POST, handleDelete);
  s_server.on("/api/seed", HTTP_POST, handleSeed);
  s_server.on("/api/stop", HTTP_POST, handleStop);
  s_server.on("/api/gesture", HTTP_POST, handleGesture);
  s_server.on("/api/control", HTTP_POST, handleControl);
  s_server.on("/api/key", HTTP_POST, handleKey);
  s_server.on("/api/schedule", HTTP_POST, handleSchedule);
  s_server.on("/api/ble", HTTP_POST, handleBle);
  s_server.on("/api/wifi", HTTP_GET, handleWifi);
  s_server.on("/api/wifi", HTTP_POST, handleWifi);
  s_server.on("/api/schedule/stop", HTTP_POST, handleScheduleStop);
  s_server.on("/api/system", HTTP_POST, handleSystem);
  s_server.on("/api/ota", HTTP_POST, handleOtaDone, handleOtaUpload);
  s_server.on("/api/ota/from-server", HTTP_POST, handleOtaFromServer);
  s_server.on("/api/page", HTTP_GET, handlePageGet);
  s_server.on("/api/page", HTTP_POST, handlePage);
  s_server.on("/api/logs", HTTP_GET, handleLogs);
  s_server.on("/api/log", HTTP_POST, handleLogAppend);
  s_server.on("/api/logs/clear", HTTP_POST, handleClearLogs);
  s_server.on("/api/led", HTTP_GET, handleLed);
  s_server.on("/api/led", HTTP_POST, handleLed);
  s_server.on("/api/remark", HTTP_POST, handleRemark);
  s_server.onNotFound(handleNotFound);

  s_server.enableCORS(true);
  s_server.begin();
  _started = true;

  LOGF("%s Web 管理已启动: %s", TAG, url().c_str());
  return true;
}

// ---------------------------------------------------------------------------
//  控制中心心跳(上电一次 + 每 HEARTBEAT_MS 一次)
//
//  为什么放在**板子**侧而不是 App 侧:板子才是"设备",
//  它自己上报 MAC/固件版本/WiFi 信息最自然,而且不依赖 App 在不在。
//
//  ⚠️ 这里会**同步阻塞**几秒(HTTP 请求)。心跳一小时才一次,
//     而且失败也不影响任何本地功能,所以可以接受。
// ---------------------------------------------------------------------------
static bool     s_hbPending    = false;   // 该发心跳了
static uint32_t s_hbLastAt     = 0;
static bool     s_hbEverOk     = false;   // 有没有成功上报过

static void hbSchedule() {
  s_hbPending = true;
  s_hbLastAt = millis();
}

/** 拼一个最小 JSON(手写,避免拉 ArduinoJson 进固件) */
static void hbJsonEscape(String &out, const String &in) {
  for (size_t i = 0; i < in.length(); i++) {
    char c = in[i];
    if (c == '"' || c == '\\') { out += '\\'; out += c; }
    else if (c == '\n') out += "\\n";
    else if (c >= 0x20) out += c;
  }
}

/** 从 JSON 里抠一个字符串字段(极简,够用) */
static bool jsonPick(const String &j, const char *key, char *out, size_t n) {
  String k = String("\"") + key + "\"";
  int i = j.indexOf(k);
  if (i < 0) return false;
  i = j.indexOf(':', i + k.length());
  if (i < 0) return false;
  i = j.indexOf('"', i);
  if (i < 0) return false;
  int e = j.indexOf('"', i + 1);
  if (e < 0) return false;
  String v = j.substring(i + 1, e);
  strncpy(out, v.c_str(), n - 1);
  out[n - 1] = 0;
  return true;
}

// ---- 后台远程升级 ----
// 服务端在心跳回复里带 "ota":{...} ⇒ 记下来,主循环空闲时执行(见 otaTick)
struct OtaCmd {
  bool     pending;
  char     ver[24];
  char     url[160];
  char     sha[72];
  uint32_t size;
};
static OtaCmd s_otaCmd = {};
// 上一次远程升级没做成的原因,下次心跳报给服务端(报成功后清空)
static char s_otaRepState[12] = {0};   // deferred = 推迟(忙) / failed = 失败
static char s_otaRepVer[24]   = {0};
static char s_otaRepMsg[128]  = {0};

static void otaReport(const char *state, const char *ver, const String &msg) {
  strncpy(s_otaRepState, state, sizeof(s_otaRepState) - 1);
  strncpy(s_otaRepVer, ver, sizeof(s_otaRepVer) - 1);
  strncpy(s_otaRepMsg, msg.c_str(), sizeof(s_otaRepMsg) - 1);
}

// 心跳里的流程列表:上次服务端确认收到的内容摘要
static bool     s_hbFlowsSynced = false;   // 开机后还没成功报过 ⇒ 必须带
static uint32_t s_hbFlowsHash = 0;         // 上次成功上报的 flowNames+flowTexts 摘要
static uint32_t s_hbFlowsAt = 0;           // 上次带全量的时刻
static const uint32_t HB_FLOWS_REFRESH_MS = 30UL * 60 * 1000;  // 没变也每 30 分钟带一次全量兜底

/** FNV-1a 32 位摘要(只用来判断"变没变") */
static uint32_t fnv1a(const String &s) {
  uint32_t h = 2166136261u;
  for (size_t i = 0; i < s.length(); i++) {
    h ^= (uint8_t)s[i];
    h *= 16777619u;
  }
  return h;
}

static void hbSend() {
  if (WiFi.status() != WL_CONNECTED) return;

  String body = "{";
  body += "\"mac\":\"" + WiFi.macAddress() + "\",";
  body += "\"name\":\"" + String(boardBaseName()) + "\",";
  // 备注:只有板子上改过、服务器还没确认时才带(服务器查重,重名就不采用)
  if (s_remarkPending) {
    String rm; hbJsonEscape(rm, String(s_remark));
    body += "\"remark\":\"" + rm + "\",\"remarkPending\":true,";
  }
  body += "\"fw\":\"" + String(FW_VERSION) + "\",";
  body += "\"ip\":\"" + WiFi.localIP().toString() + "\",";
  body += "\"channel\":\"stable\",";
  // 告诉服务端:这版固件认得心跳回复里的远程升级命令(老固件没有这个字段)
  body += "\"caps\":\"ota-remote\",";
  if (s_otaRepState[0]) {
    String m; hbJsonEscape(m, String(s_otaRepMsg));
    body += "\"otaReport\":{\"state\":\"" + String(s_otaRepState) + "\",\"ver\":\""
          + String(s_otaRepVer) + "\",\"msg\":\"" + m + "\"},";
  }
  body += "\"heap\":" + String(ESP.getFreeHeap()) + ",";
  body += "\"uptime\":" + String(millis() / 1000) + ",";
  body += "\"hid\":" + String(Touch.ready() ? "true" : "false") + ",";
  body += "\"flows\":" + String(Scripts.count()) + ",";

  // 流程**名字**列表(服务端看板要显示,不能只有数量)
  String flowsPart = "\"flowNames\":[";
  for (uint8_t i = 0; i < Scripts.count(); i++) {   // 全部上报(以前只报前 20 个)
    const Script *sc = Scripts.at(i);
    if (!sc) continue;
    if (i) flowsPart += ",";
    String nm; hbJsonEscape(nm, String(sc->name));
    flowsPart += "\"" + nm + "\"";
  }
  flowsPart += "],";

  // 流程**内容**也一起上报 —— 服务端留底,万一被误删可以恢复。
  // (实测踩过:自动化点击误触「删除」,流程就没了)
  flowsPart += "\"flowTexts\":{";
  for (uint8_t i = 0; i < Scripts.count(); i++) {   // 全部上报(以前只报前 20 个)
    const Script *sc = Scripts.at(i);
    if (!sc) continue;
    if (i) flowsPart += ",";
    String nm, tx;
    hbJsonEscape(nm, String(sc->name));
    hbJsonEscape(tx, scriptToText(*sc));
    flowsPart += "\"" + nm + "\":\"" + tx + "\"";
  }
  flowsPart += "},";

  // 流程没变就不带(省流量 + 服务端不用逐行比对)。
  // ⚠️ 要么名字+内容一起带,要么都不带:旧服务端见到 flowNames 但没 flowTexts
  //    会把留底的内容全写成 NULL;都不带它就整段跳过。
  const uint32_t flowsHash = fnv1a(flowsPart);
  const bool sendFlows = !s_hbFlowsSynced || flowsHash != s_hbFlowsHash ||
                         millis() - s_hbFlowsAt >= HB_FLOWS_REFRESH_MS;
  if (sendFlows) body += flowsPart;

  // 当前跑的流程 + 下一次定时(App/服务端都关心)
  if (s_state.running && s_state.lastName[0]) {
    String nm; hbJsonEscape(nm, String(s_state.lastName));
    body += "\"running\":\"" + nm + "\",";
  } else {
    body += "\"running\":\"\",";
  }
  // 最近要执行的脚本(大麦那种定时是 App 管的,板子只报"频率循环")
  if (s_schedActive) {
    if (s_schedName[0]) {
      String nm; hbJsonEscape(nm, String(s_schedName));
      body += "\"next\":{\"name\":\"" + nm + "\",\"at\":"
            + String((uint32_t)(millis() + (s_schedNextAt - millis()))) + "},";
    }
  }

  // WiFi 名称 / 信号 / 信道(密码不上报 —— 板子只存不传)
  body += "\"wifi\":{";
  body += "\"ssid\":\"" + WiFi.SSID() + "\",";
  body += "\"rssi\":" + String(WiFi.RSSI()) + ",";
  body += "\"channel\":" + String(WiFi.channel());
  body += "}}";

  HTTPClient http;
  http.setConnectTimeout(4000);
  http.setTimeout(6000);
  String url = String(SERVER_URL) + "/api/v1/board/heartbeat";
  if (!http.begin(url)) { LOGF("%s 心跳:连不上服务端", TAG); return; }
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Token", SERVER_TOKEN);

  int code = http.POST(body);
  if (code == 200) {
    String resp = http.getString();
    s_hbEverOk = true;
    if (sendFlows) {
      s_hbFlowsSynced = true;
      s_hbFlowsHash = flowsHash;
      s_hbFlowsAt = millis();
    }
    // 服务端那边没有这块板子的流程(新库 / 被删过)⇒ 下次带全量
    if (resp.indexOf("\"needFlows\":true") >= 0) {
      s_hbFlowsSynced = false;
      LOGF("%s 服务端要求补报流程列表", TAG);
    }
    bool has = (resp.indexOf("\"hasUpdate\":true") >= 0);
    if (has) {
      char buf[128] = {0};
      if (jsonPick(resp, "version", buf, sizeof(buf))) strncpy(s_fwVersion, buf, sizeof(s_fwVersion) - 1);
      if (jsonPick(resp, "url", buf, sizeof(buf)))     strncpy(s_fwUrl, buf, sizeof(s_fwUrl) - 1);
      if (jsonPick(resp, "sha256", buf, sizeof(buf)))  strncpy(s_fwSha, buf, sizeof(s_fwSha) - 1);
      if (jsonPick(resp, "notes", buf, sizeof(buf)))   strncpy(s_fwNotes, buf, sizeof(s_fwNotes) - 1);
      int p = resp.indexOf("\"size\":");
      if (p >= 0) s_fwSize = (uint32_t)resp.substring(p + 7).toInt();
      if (!s_fwHasUpdate) {
        LOGF("%s 服务端说有新固件:%s", TAG, s_fwVersion);
      }
      s_fwHasUpdate = true;
    } else {
      s_fwHasUpdate = false;
    }
    LOGF("%s 心跳成功(固件更新:%s,%u 字节,%s)", TAG, s_fwHasUpdate ? "有" : "无",
         (unsigned)body.length(), sendFlows ? "含流程列表" : "流程未变未带");
    s_otaRepState[0] = 0;   // 推迟/失败原因已经报上去了

    // ---- 备注:以服务器为准(回复里总带服务器那份;老服务端不带就不动)----
    if (resp.indexOf("\"remark\":") >= 0) {
      char rm[REMARK_MAX_BYTES + 4] = {0};
      jsonPick(resp, "remark", rm, sizeof(rm));
      const bool wasPending = s_remarkPending;
      const String mine(s_remark);
      String why;
      if (!remarkApplyFromServer(String(rm), why)) {
        LOGF("%s 服务器给的备注不合法(%s),忽略", TAG, why.c_str());
      } else if (wasPending) {
        if (mine == rm) LOGF("%s 板子上改的备注已同步到服务器:「%s」", TAG, rm);
        else LOGF("%s 板子上改的备注「%s」没被服务器采用(重名?),按服务器改回「%s」",
                  TAG, mine.c_str(), rm);
      }
    }

    // ---- 下行命令:后台远程升级 ----
    // ⚠️ 只在 "ota":{...} 这一段里取字段 —— 回复里 firmware 段也有 version/url/sha256
    int oi = resp.indexOf("\"ota\":{");
    if (oi >= 0) {
      int oe = resp.indexOf('}', oi);
      String o = resp.substring(oi, oe > oi ? oe + 1 : resp.length());
      OtaCmd c = {};
      bool ok = jsonPick(o, "version", c.ver, sizeof(c.ver)) &&
                jsonPick(o, "url", c.url, sizeof(c.url));
      jsonPick(o, "sha256", c.sha, sizeof(c.sha));
      int p = o.indexOf("\"size\":");
      if (p >= 0) c.size = (uint32_t)o.substring(p + 7).toInt();
      if (ok && strcmp(c.ver, FW_VERSION) != 0) {
        c.pending = true;
        s_otaCmd = c;
        LOGF("%s 收到后台远程升级命令:%s → %s(%u 字节)", TAG, FW_VERSION, c.ver, (unsigned)c.size);
      }
    }

    // ---- 下行命令:LED ----
    // ⚠️ 服务端够不到内网的板子,这是**唯一**的下行通道:
    //    板子每次心跳顺路把命令捎回来。所以最长要等一个心跳周期(2 分钟)。
    //    "立刻亮"请走 App(App 和板子有直连)。
    // ⚠️ 服务器下行也拦:运行中不接受远程改灯(和 HTTP/BLE 一致)
    if (resp.indexOf("\"led\"") >= 0 && s_state.running) {
      LOGLN("[led] 收到服务端点灯命令,但正在执行流程 → 忽略");
    } else if (resp.indexOf("\"led\"") >= 0) {
      if (resp.indexOf("\"blink\":true") >= 0) {
        LOGLN("[led] 收到服务端命令:找板子模式");
        Led::identify();
      } else if (resp.indexOf("\"off\":true") >= 0) {
        LOGLN("[led] 收到服务端命令:关灯");
        Led::off();
      } else if (resp.indexOf("\"auto\":true") >= 0) {
        LOGLN("[led] 收到服务端命令:回到自动");
        Led::autoMode();
      } else {
        int r = 0, g = 0, b = 0, ms = 0;
        char tmp[24];
        if (jsonPick(resp, "r", tmp, sizeof(tmp)))  r  = atoi(tmp);
        if (jsonPick(resp, "g", tmp, sizeof(tmp)))  g  = atoi(tmp);
        if (jsonPick(resp, "b", tmp, sizeof(tmp)))  b  = atoi(tmp);
        if (jsonPick(resp, "ms", tmp, sizeof(tmp))) ms = atoi(tmp);
        LOGF("[led] 收到服务端命令:LED %d %d %d %dms", r, g, b, ms);
        Led::set((uint8_t)constrain(r, 0, 255), (uint8_t)constrain(g, 0, 255),
                 (uint8_t)constrain(b, 0, 255), (uint32_t)ms);
      }
    }
  } else {
    LOGF("%s 心跳失败:HTTP %d", TAG, code);
  }
  http.end();
}

/** 把固件更新信息拼成 JSON 给 /api/status 用 */
static String fwUpdateJson() {
  if (!s_fwHasUpdate) return String("{\"hasUpdate\":false}");
  String j = "{\"hasUpdate\":true,\"version\":\"" + String(s_fwVersion) + "\",";
  j += "\"size\":" + String(s_fwSize) + ",";
  j += "\"url\":\"" + String(s_fwUrl) + "\",";
  j += "\"sha256\":\"" + String(s_fwSha) + "\",";
  String n; hbJsonEscape(n, String(s_fwNotes));
  j += "\"notes\":\"" + n + "\"}";
  return j;
}

// ---------------------------------------------------------------------------
//  UDP 自动发现
//
//  为什么需要:板子走 DHCP,换网络/重启后 IP 会变。
//  手机 App 广播一句 "TOUCHPAD_DISCOVER",板子回一句 "TOUCHPAD_HERE <ip> <port>",
//  App 就知道板子在哪了 —— 不用手输 IP。
//
//  为什么用 UDP 广播而不是 mDNS:很多路由器默认不转发组播,
//  而广播(255.255.255.255)在家庭网络里基本都能通。
// ---------------------------------------------------------------------------
#define DISCOVER_PORT   45678
#define DISCOVER_MAGIC  "TOUCHPAD_DISCOVER"
#define DISCOVER_REPLY  "TOUCHPAD_HERE"

static WiFiUDP s_discoverUdp;
static bool    s_discoverReady = false;

static void discoverBegin() {
  if (s_discoverReady) return;
  if (s_discoverUdp.begin(DISCOVER_PORT)) {
    s_discoverReady = true;
    LOGF("%s 自动发现已就绪:UDP %u", TAG, (unsigned)DISCOVER_PORT);
  }
}

static void discoverLoop() {
  if (!s_discoverReady) return;
  int sz = s_discoverUdp.parsePacket();
  if (sz <= 0) return;
  char buf[64] = {0};
  int len = s_discoverUdp.read(buf, sizeof(buf) - 1);
  if (len <= 0) return;
  buf[len] = '\0';
  if (strstr(buf, DISCOVER_MAGIC) == nullptr) return;

  String myIp = (WiFi.status() == WL_CONNECTED) ? WiFi.localIP().toString()
                                                : WiFi.softAPIP().toString();
  String reply = String(DISCOVER_REPLY) + " " + myIp + " " + String(HTTP_PORT);
  s_discoverUdp.beginPacket(s_discoverUdp.remoteIP(), s_discoverUdp.remotePort());
  s_discoverUdp.write((const uint8_t *)reply.c_str(), reply.length());
  s_discoverUdp.endPacket();
  LOGF("%s 收到发现请求,已回复 %s", TAG, myIp.c_str());
}

/** 心跳调度的对外入口(由 loop 调) */
static void heartbeatTick();

/**
 * 执行后台下发的远程升级(心跳回复里收到的)。
 *
 * ⚠️ loop 也会在流程的等待钩子里被调到 —— 所以先看忙不忙:
 *    流程在跑 / 排队 / 频率循环开着 ⇒ 这次不升,报「推迟」,服务端下次心跳再发。
 */
static void otaTick() {
  if (!s_otaCmd.pending) return;
  s_otaCmd.pending = false;
  String why;
  if (otaBusyReason(why)) {
    LOGF("%s 远程升级推迟:%s", TAG, why.c_str());
    otaReport("deferred", s_otaCmd.ver, why);
    return;
  }
  LOGF("%s 开始远程升级:%s → %s", TAG, FW_VERSION, s_otaCmd.ver);
  String err;
  if (otaDownloadAndFlash(s_otaCmd.url, s_otaCmd.sha, s_otaCmd.size, err)) {
    LOGF("%s 远程升级写入成功,重启进 %s", TAG, s_otaCmd.ver);
    delay(500);
    ESP.restart();
  }
  otaReport("failed", s_otaCmd.ver, err);
  hbSchedule();   // 失败原因尽快报上去
}

void NetManager::loop() {
  if (_started) s_server.handleClient();
  // ⚠️ **热点模式也要开发现服务**。
  //    首次配置时板子还没连上任何 WiFi,只能开热点;
  //    这时手机连着板子的热点,App 广播一下就能找到它(板子在 192.168.4.1),
  //    否则 App 就完全没法引导用户配网 —— 死循环。
  if (_started && (staConnected() || _ap)) {
    discoverBegin();
    discoverLoop();
  }
  heartbeatTick();
  otaTick();
}

// 流程执行中心跳最多推迟这么久。
// 服务端 5 分钟没心跳就判离线(ONLINE_MS),心跳每 2 分钟一次 ⇒ 最多再推 150 秒还来得及。
static const uint32_t HB_DEFER_MAX_MS = 150000UL;
static uint32_t s_hbDeferSince = 0;      // 开始推迟的时刻(0 = 没在推迟)

static void heartbeatTick() {
  if (WiFi.status() != WL_CONNECTED) return;
  // 上电 8 秒后第一次(等网络稳),之后每 HEARTBEAT_MS 一次
  if (!s_hbLastAt) { s_hbLastAt = millis(); s_hbPending = true; }
  if (s_hbPending && (millis() - s_hbLastAt) > (s_hbEverOk ? 1000UL : 8000UL)) {
    // ⚠️ 流程执行中不发:hbSend 是同步 HTTP,最长要阻塞约 10 秒(连接 4s + 读 6s),
    //    而这里会被流程的等待钩子调到 ⇒ 流程里的 wait 被拉长、点击时机全乱。
    //    推迟到流程结束后再发(主循环下一轮就会发);流程特别长时最多推迟 HB_DEFER_MAX_MS。
    if (Scripts.running()) {
      if (s_hbDeferSince == 0) {
        s_hbDeferSince = millis();
        LOGF("%s 心跳推迟:流程执行中", TAG);
      }
      if (millis() - s_hbDeferSince < HB_DEFER_MAX_MS) return;
      LOGF("%s 流程已执行超过 %lu 秒,心跳不再推迟", TAG,
           (unsigned long)(HB_DEFER_MAX_MS / 1000));
    } else if (s_hbDeferSince) {
      LOGF("%s 流程已结束,补发推迟的心跳(推迟了 %lu ms)", TAG,
           (unsigned long)(millis() - s_hbDeferSince));
    }
    s_hbDeferSince = 0;
    s_hbPending = false;
    s_hbLastAt = millis();
    hbSend();
  }
  if (!s_hbPending && (millis() - s_hbLastAt) > HEARTBEAT_MS) {
    s_hbPending = true;
  }
}

void NetManager::stop() {
  if (!_started) return;
  s_server.stop();
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  _started = false;
  _ap = false;
  LOGF("%s WiFi 已关闭(射频让给 BLE)", TAG);
}

bool NetManager::staConnected() const { return WiFi.status() == WL_CONNECTED; }
bool NetManager::apActive() const { return _ap; }

String NetManager::ip() const {
  if (WiFi.status() == WL_CONNECTED) return WiFi.localIP().toString();
  if (_ap) return WiFi.softAPIP().toString();
  return String("0.0.0.0");
}

String NetManager::modeName() const {
  if (WiFi.status() == WL_CONNECTED) return String("STA(局域网)");
  if (_ap) return String("AP(板子热点)");
  return String("未联网");
}

String NetManager::url() const { return String("http://") + ip() + "/"; }

// ⚠️ 必须返回**同一个** s_net。
//    原来这里是 `static NetManager inst;` —— 又造了一个对象,
//    结果 begin() 在 inst 上开了热点,而 handleStatus 读 s_net,
//    _ap 永远是 false → AP 模式下状态接口报 "OFF / 0.0.0.0"。
//    STA 模式下因为 WiFi.status() 是全局的,侥幸看不出来。
NetManager &netManager() { return s_net; }

// ---------------------------------------------------------------------------
//  脚本执行与定时调度(由 main.cpp 的 loop() 调用)
// ---------------------------------------------------------------------------
static bool runHook(uint16_t step, uint16_t total, void *ctx) {
  RunState *st = (RunState *)ctx;
  if (st) {
    st->step = (uint8_t)step;
    st->totalSteps = (uint8_t)total;
  }
  return !Scripts.stopRequested();
}

void netServiceScripts() {
  // ---- 1) 一次性执行请求 ----
  RunRequest req;
  if (takeScriptRunRequest(req) && req.index >= 0) {
    const Script *sc = Scripts.at((uint8_t)req.index);
    if (sc && Touch.ready() && !s_state.running) {
      s_state.running = true;
      s_state.index = (uint8_t)req.index;
      s_state.step = 0;
      s_state.totalSteps = sc->stepCount;
      s_state.repeatDone = 0;
      s_state.repeatTotal = req.repeat;
      strncpy(s_state.lastName, sc->name, sizeof(s_state.lastName) - 1);
      s_state.lastError[0] = '\0';

      bool allOk = true;
      uint32_t t0 = millis();
      for (uint16_t r = 0; r < req.repeat; r++) {
        s_state.repeatDone = (uint16_t)(r + 1);
        ScriptResult res = Scripts.run((uint8_t)req.index, runHook, &s_state);
        if (!res.ok) {
          allOk = false;
          strncpy(s_state.lastError, res.error, sizeof(s_state.lastError) - 1);
          break;
        }
        if (r + 1 < req.repeat && req.intervalMs) delay(req.intervalMs);
      }
      s_state.elapsedMs = millis() - t0;
      s_state.running = false;
      s_state.runCount++;
      s_state.lastOk = allOk;
      LOGF("%s 执行结束: %s", TAG, allOk ? "成功" : s_state.lastError);
    } else if (!Touch.ready()) {
      LOGF("%s HID 未就绪,跳过 Web 触发的脚本", TAG);
    }
  }

  // ---- 2) 定时调度 ----
  if (s_schedActive && !s_state.running && Touch.ready()) {
    // 到点了就自己停(时长控制在**板子侧**,App 挂了也能停)
    if (s_schedEndAt && (int32_t)(millis() - s_schedEndAt) >= 0) {
      uint32_t ran = s_schedDurationMs / 1000;
      s_schedActive = false;
      s_schedEndAt = 0;
      LOGF("%s 定时循环到时停止(已连续执行 %u 秒)", TAG, (unsigned)ran);
      return;
    }
    if ((int32_t)(millis() - s_schedNextAt) >= 0) {
      s_schedNextAt = millis() + s_schedIntervalMs;
      int idx = Scripts.indexOf(s_schedName);
      if (idx >= 0) s_schedIndex = (uint8_t)idx;
      const Script *sc = (idx >= 0) ? Scripts.at(s_schedIndex) : nullptr;
      if (sc == nullptr) {
        LOGF("%s 定时循环的流程「%s」已不存在(被删除?),停止循环", TAG, s_schedName);
      }
      if (sc) {
        s_state.running = true;
      // ⚠️⚠️ 灯必须在这里点亮,不能靠主循环"轮询 isRunning()" ——
      //     流程是在本函数里**同步跑完**的,running 在同一次调用里
      //     先 true 再 false,主循环永远看不到中间的 true(实测灯不变黄)。
      Led::setState(Led::ST_RUNNING);
        s_state.index = s_schedIndex;
        s_state.totalSteps = sc->stepCount;
        s_state.repeatDone = 1;
        s_state.repeatTotal = 1;
        strncpy(s_state.lastName, sc->name, sizeof(s_state.lastName) - 1);
        uint32_t t0 = millis();
        ScriptResult res = Scripts.run(s_schedIndex, runHook, &s_state);
        s_state.elapsedMs = millis() - t0;
        s_state.running = false;
        s_state.runCount++;
        s_state.lastOk = res.ok;
        if (!res.ok) {
          strncpy(s_state.lastError, res.error, sizeof(s_state.lastError) - 1);
          LOGF("%s 定时执行失败: %s", TAG, res.error);
        }
      } else {
        s_schedActive = false;
      }
    }
  }
}
