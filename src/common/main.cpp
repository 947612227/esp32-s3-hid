// ============================================================================
//  ESP32-S3 HID 触摸注入开发板 —— 主程序
//
//  架构:
//    原生 USB 口(GPIO19/20) ──> 手机/PC,作为 HID 复合设备(触摸屏+键盘+Consumer+鼠标)
//    UART0(GPIO43/44)        ──> PC,115200,接收控制命令 + 打印日志
//
//  注意:USB-OTG 与 USB-Serial-JTAG 共用 PHY,本固件把原生 USB 交给 HID 之后,
//        UART0 仍然可用(它是独立的 UART 外设),所以"手机一根线 + PC 一根线"并存。
//
//  命令协议见 README.md
// ============================================================================
#include <Arduino.h>
#include <LittleFS.h>
#include <ctype.h>

#include "hid_touch.h"
#include "hid_report_map.h"
#include "led.h"
#include "log_buffer.h"
#include "net_manager.h"
#include "script_engine.h"

// ---------------------------------------------------------------------------
//  引脚
// ---------------------------------------------------------------------------
static constexpr int PIN_BOOT_BTN = 0;   // 板载 BOOT 键(GPIO0)
static uint32_t btnDownAt = 0;          // BOOT 按下时刻(区分短按/长按)

// 可选:把控制协议挪到 UART1(GPIO17=TX / GPIO18=RX),UART0 只留日志
#ifdef CONTROL_ON_UART1
static constexpr int PIN_CTRL_TX = 17;
static constexpr int PIN_CTRL_RX = 18;
#endif

// 上电是否自动跑一段演示手势(默认关闭,避免误触别人的屏幕)
#ifndef EJ_TOUCH_DEMO
#define EJ_TOUCH_DEMO 0
#endif

// ---------------------------------------------------------------------------
//  串口
// ---------------------------------------------------------------------------
static Stream *Ctrl = &Serial;   // 控制协议输入

static char   lineBuf[128];
static size_t lineLen = 0;

static void printHelp() {
  Serial.println();
  Serial.println(F("=== ESP32-S3 HID Touch Injector ==="));
  Serial.println(F("坐标均为归一化 0..1,x 向右, y 向下;时长单位 ms"));
  Serial.println(F("  T x y [ms]              点击(默认 40ms)"));
  Serial.println(F("  D x y [gap]             双击"));
  Serial.println(F("  H x y [ms]              长按(默认 900ms)"));
  Serial.println(F("  S x1 y1 x2 y2 [ms]      直线滑动(默认 220ms)"));
  Serial.println(F("  C x1 y1 cx cy x2 y2 [ms] 贝塞尔曲线滑动"));
  Serial.println(F("  P x1 y1 x2 y2 x3 y3 x4 y4 [ms] 双指滑动"));
  Serial.println(F("  Z cx cy gap1 gap2 [ms]  双指捏合/张开"));
  Serial.println(F("  M dx dy                 鼠标相对移动(-127..127)"));
  Serial.println(F("  B [n]                   鼠标左键点击 n 次(默认 1)"));
  Serial.println(F("  K <usage>               键盘按键(USB HID usage 十进制)"));
  Serial.println(F("  V <usage>               Consumer 键(0xE9 音量+, 0xEA 音量-)"));
  Serial.println(F("  G                       自检:点四个角 + 中心"));
  Serial.println(F("  U                       立即抬起全部触点"));
  Serial.println(F("  R                       复位状态"));
  Serial.println(F("  --- 脚本引擎(存在 Flash 上,断电不丢)---"));
  Serial.println(F("  LIST [n]                列出脚本;[n] 可选,列出某个脚本的步骤"));
  Serial.println(F("  RUN <name|序号>         执行脚本"));
  Serial.println(F("  DEL <name>              删除脚本"));
  Serial.println(F("  RELOAD                  重新扫描 /scripts 目录"));
  Serial.println(F("  SAVE <name>             进入录制:把接下来的命令按行存起来"));
  Serial.println(F("  END                     结束录制并保存"));
  Serial.println(F("  CANCEL                  放弃录制"));
  Serial.println(F("  FILES                   查看文件系统占用"));
      Serial.println(F("    LED r g b [ms] | AUTO | OFF   板载 RGB 灯;BLINK = 找板子"));
    Serial.println(F("      LED OFF   = 关灯(保持熄灭)   LED AUTO = 回到状态色"));
  Serial.println(F("  NET                     查看网络状态(IP / 模式 / 访问地址)"));
  Serial.println(F("  WIFI OFF                关闭 WiFi,把射频全部让给 BLE(降低触摸延迟)"));
  Serial.println(F("  WIFI ON                 重新开启 WiFi(需要重启)  "));
  Serial.println(F("  --- 脚本内可用指令 ---"));
  Serial.println(F("  T/D/H/S/C/P/Z/K/V/wait/repeat/goto,坐标支持 0.5 或 50%"));
  Serial.println(F("  ?                       本帮助"));
  Serial.println();
}

// 解析空格分隔的浮点参数
static uint8_t parseFloats(const char *s, float *out, uint8_t maxN) {
  uint8_t n = 0;
  while (*s && n < maxN) {
    while (*s == ' ') s++;
    if (!*s) break;
    char *end = nullptr;
    float v = strtof(s, &end);
    if (end == s) break;
    out[n++] = v;
    s = end;
  }
  return n;
}

// 解析整数参数
static uint8_t parseInts(const char *s, long *out, uint8_t maxN) {
  uint8_t n = 0;
  while (*s && n < maxN) {
    while (*s == ' ') s++;
    if (!*s) break;
    char *end = nullptr;
    long v = strtol(s, &end, 0);
    if (end == s) break;
    out[n++] = v;
    s = end;
  }
  return n;
}

// 自检:依次点屏幕四角与中心。用来第一眼确认坐标映射是否正确。
static void selfTest() {
  Serial.println(F("[selftest] 依次点击 左上/右上/左下/右下/中心"));
  if (!Touch.ready()) {
    Serial.println(F("[selftest] ✗ 传输层未就绪(主机没枚举成功?),跳过自检"));
    return;
  }
  const float pts[5][2] = {
      {0.05f, 0.05f}, {0.95f, 0.05f}, {0.05f, 0.95f}, {0.95f, 0.95f},
      {0.50f, 0.50f}};
  for (uint8_t i = 0; i < 5; i++) {
    Serial.printf("[selftest] tap %.2f,%.2f  (ready=%d)\n", pts[i][0], pts[i][1],
                  (int)Touch.ready());
    Touch.tap(pts[i][0], pts[i][1], 70);
    delay(500);
  }
  Touch.releaseAll();
  Serial.println(F("[selftest] 完成"));
}

// ---------------------------------------------------------------------------
//  脚本引擎:串口命令
// ---------------------------------------------------------------------------
// 录制状态:SAVE <name> 之后收到的每一行都会存进缓冲区,END 时落盘
static bool   s_recording = false;
static char   s_recName[SCRIPT_NAME_LEN] = {0};
static String s_recBuf;

static void printScriptList(uint8_t index, bool showSteps) {
  const Script *sc = Scripts.at(index);
  if (sc == nullptr) return;
  Serial.printf("  [%u] %-16s %u 步\n", (unsigned)index, sc->name,
                (unsigned)sc->stepCount);
  if (!showSteps) return;
  for (uint8_t i = 0; i < sc->stepCount; i++) {
    const ScriptStep &st = sc->steps[i];
    Serial.printf("        %2u) %-9s", (unsigned)i, stepOpName(st.op));
    switch (st.op) {
      case StepOp::Tap:
      case StepOp::DoubleTap:
      case StepOp::Hold:
        Serial.printf("(%.3f, %.3f) %ums", st.a, st.b, (unsigned)st.ms);
        break;
      case StepOp::Swipe:
        Serial.printf("(%.3f,%.3f)->(%.3f,%.3f) %ums", st.a, st.b, st.c, st.d,
                      (unsigned)st.ms);
        break;
      case StepOp::Curve:
        Serial.printf("(%.3f,%.3f) c(%.3f,%.3f) ->(%.3f,%.3f) %ums", st.a, st.b,
                      st.c, st.d, (float)st.e / 255.0f, (float)st.f / 255.0f,
                      (unsigned)st.ms);
        break;
      case StepOp::TwoFinger:
        Serial.printf("(%.3f,%.3f)->(%.3f,%.3f) gap=%.2f %ums", st.a, st.b, st.c,
                      st.d, (float)st.e / 255.0f, (unsigned)st.ms);
        break;
      case StepOp::Pinch:
        Serial.printf("c(%.3f,%.3f) gap %.2f->%.2f %ums", st.a, st.b, st.c, st.d,
                      (unsigned)st.ms);
        break;
      case StepOp::Key:
      case StepOp::Consumer:
        Serial.printf("usage=%u", (unsigned)st.e);
        break;
      case StepOp::Delay:
        Serial.printf("%ums", (unsigned)st.ms);
        break;
      case StepOp::Goto:
        Serial.printf("-> 第 %u 步", (unsigned)st.next);
        break;
      case StepOp::Repeat:
        Serial.printf("x%u(其后全部步骤)", (unsigned)st.repeat);
        break;
      default:
        break;
    }
    Serial.println();
  }
}

// 处理脚本相关命令。返回 true 表示这条命令已被处理
static bool handleScriptCommand(const char *word, const char *args) {
  if (!strcasecmp(word, "LIST")) {
    if (Scripts.count() == 0) {
      Serial.println(F("(还没有脚本。用 SAVE <name> 录制,或 FILES 看文件系统)"));
      return true;
    }
    int want = -1;
    if (*args) want = atoi(args);
    Serial.printf("共 %u 个脚本:\n", (unsigned)Scripts.count());
    for (uint8_t i = 0; i < Scripts.count(); i++) {
      // want >= 0 时只展开目标脚本的步骤,其余只列一行
      printScriptList(i, want >= 0 && (int)i == want);
    }
    return true;
  }

  if (!strcasecmp(word, "RUN")) {
    while (*args == ' ') args++;
    if (!*args) { Serial.println(F("ERR RUN 需要脚本名或序号")); return true; }
    const Script *sc = nullptr;
    uint8_t idx = 0;
    if (isdigit((unsigned char)*args)) {
      idx = (uint8_t)atoi(args);
      sc = Scripts.at(idx);
    } else {
      sc = Scripts.find(args);
      if (sc) {
        for (uint8_t i = 0; i < Scripts.count(); i++) {
          if (Scripts.at(i) == sc) { idx = i; break; }
        }
      }
    }
    if (sc == nullptr) {
      Serial.printf("ERR 找不到脚本 '%s'(先 LIST 看看)\n", args);
      return true;
    }
    if (!Touch.ready()) {
      Serial.println(F("ERR 传输层未就绪(没连接/没订阅),拒绝执行"));
      return true;
    }
    ScriptResult r = Scripts.run(idx);
    if (r.ok) {
      LOGF("OK 执行完成:%u 步", (unsigned)r.executed);
    } else {
      LOGF("ERR 执行失败:%s", r.error);
    }
    return true;
  }

  if (!strcasecmp(word, "DEL") || !strcasecmp(word, "DELETE")) {
    while (*args == ' ') args++;
    if (!*args) { Serial.println(F("ERR DEL 需要脚本名")); return true; }
    if (Scripts.remove(args)) Serial.printf("OK 已删除 %s\n", args);
    else Serial.printf("ERR 删除失败(不存在?)\n");
    return true;
  }

  if (!strcasecmp(word, "RELOAD")) {
    Scripts.reload();
    Serial.printf("OK 重新扫描完成,共 %u 个脚本\n", (unsigned)Scripts.count());
    return true;
  }

  if (!strcasecmp(word, "NET")) {
    Serial.printf("网络模式: %s\n", netManager().modeName().c_str());
    Serial.printf("IP      : %s\n", netManager().ip().c_str());
    Serial.printf("访问地址: %s\n", netManager().url().c_str());
    Serial.printf("HID     : %s\n", Touch.ready() ? "就绪" : "未连接");
    Serial.printf("脚本    : %u 个\n", (unsigned)Scripts.count());
    return true;
  }

  if (!strcasecmp(word, "WIFI")) {
    while (*args == ' ') args++;
    if (!strcasecmp(args, "OFF")) {
      netManager().stop();
      Serial.println(F("OK WiFi 已关闭 —— 射频让给 BLE,触摸延迟应改善"));
      Serial.println(F("   恢复需要重启板子(或以后加运行时重连)"));
    } else if (!strcasecmp(args, "ON")) {
      Serial.println(F("WiFi 重新开启请重启板子(WIFI OFF 后射频已释放,重新初始化需要重启)"));
    } else {
      Serial.println(F("用法: WIFI OFF | WIFI ON"));
    }
    return true;
  }

  // ---- LED:板载 RGB 灯(用来在几块同名板子里认出这一块)----
  //   LED r g b [ms]  设颜色;ms 省略表示一直保持
  //   LED             看当前颜色
  //   BLINK           找板子模式(快-快-慢 + 绿色常亮 60 秒)
  if (!strcasecmp(word, "LED")) {
    // ⚠️⚠️ 必须跳过前导空格 ——
    //     上游是 `rest = line + strlen(word)`,对 "LED AUTO" 得到的是
    //     **" AUTO"**(带一个空格)。strcasecmp(" AUTO","AUTO") 不相等,
    //     于是 AUTO/OFF 全都掉到下面的 sscanf 分支报"用法错误"。
    //     而 `LED 255 0 0` 能过,是因为 sscanf 会自动跳空格 ——
    //     所以这个 bug 只在**纯字母参数**上暴露,特别隐蔽。
    //     其它 handler(RUN/DEL/…)都写了这句,我漏了。
    while (*args == ' ') args++;
    if (args[0] == '\0') {
      Serial.printf("LED 当前模式:%s\r\n", Led::modeName());
      Serial.println(F("  LED r g b [ms]  设颜色(ms 省略=一直保持;不填=关灯)"));
      Serial.println(F("  LED AUTO        回到状态色(未连接蓝/已连接绿/执行中黄)"));
      Serial.println(F("  LED OFF         关灯"));
      Serial.println(F("  BLINK           找板子(快-快-慢 + 绿常亮 60 秒)"));
      return true;
    }
    if (!strcasecmp(args, "AUTO")) {
      Led::autoMode();
      Serial.println(F("LED 已回到自动(随状态变色)"));
      return true;
    }
    if (!strcasecmp(args, "OFF")) {
      Led::off();
      Serial.println(F("LED 已关闭(手动保持熄灭)"));
      return true;
    }
    int r = 0, g = 0, b = 0;
    unsigned long ms = 0;
    int n = sscanf(args, "%d %d %d %lu", &r, &g, &b, &ms);
    if (n < 3) { Serial.println(F("ERR 用法: LED r g b [ms]")); return true; }
    Led::set((uint8_t)r, (uint8_t)g, (uint8_t)b, (uint32_t)ms);
    Serial.printf("LED %d %d %d %lums\r\n", r, g, b, ms);
    return true;
  }
  if (!strcasecmp(word, "BLINK") || !strcasecmp(word, "FINDME")) {
    Led::identify();
    Serial.println(F("LED 找板子模式已启动(快-快-慢 + 绿色常亮 60 秒)"));
    return true;
  }

  if (!strcasecmp(word, "FILES")) {
    Serial.printf("LittleFS: 已用 %u KB / 总 %u KB\n",
                  (unsigned)(LittleFS.usedBytes() / 1024),
                  (unsigned)(LittleFS.totalBytes() / 1024));
    return true;
  }

  if (!strcasecmp(word, "SAVE")) {
    while (*args == ' ') args++;
    if (!*args) { Serial.println(F("ERR SAVE 需要脚本名")); return true; }
    strncpy(s_recName, args, sizeof(s_recName) - 1);
    s_recName[sizeof(s_recName) - 1] = '\0';
    s_recBuf = "";
    s_recording = true;
    Serial.printf("OK 录制中:%s\n", s_recName);
    Serial.println(F("   接下来每行都会成为脚本步骤(用脚本语法,如 T 0.5 0.5)"));
    Serial.println(F("   发送 END 保存,CANCEL 放弃"));
    return true;
  }

  if (!strcasecmp(word, "CANCEL")) {
    if (!s_recording) { Serial.println(F("(当前没有在录制)")); return true; }
    s_recording = false;
    s_recBuf = "";
    Serial.println(F("OK 已放弃录制"));
    return true;
  }

  if (!strcasecmp(word, "END")) {
    if (!s_recording) { Serial.println(F("(当前没有在录制)")); return true; }
    s_recording = false;
    char err[64] = {0};
    if (Scripts.saveFromText(s_recName, s_recBuf.c_str(), err, sizeof(err))) {
      LOGF("OK 已保存 %s(%u 字节)", s_recName, (unsigned)s_recBuf.length());
    } else {
      LOGF("ERR 保存失败:%s", err);
    }
    s_recBuf = "";
    return true;
  }

  return false;
}

// ---------------------------------------------------------------------------
//  命令处理
// ---------------------------------------------------------------------------
static void handleLine(char *line) {
  // 跳过前导空格
  while (*line == ' ' || *line == '\t') line++;
  if (!*line) return;

  // ---- 录制模式:除 END/CANCEL 外,所有行原样存起来,不执行 ----
  if (s_recording) {
    char word[16] = {0};
    sscanf(line, "%15s", word);
    if (!strcasecmp(word, "END") || !strcasecmp(word, "CANCEL")) {
      handleScriptCommand(word, line + strlen(word));
      return;
    }
    s_recBuf += line;
    s_recBuf += "\n";
    Serial.printf("  + %s\n", line);
    return;
  }

  // ---- 多字符命令:先按"整词"匹配脚本命令 ----
  {
    char word[16] = {0};
    sscanf(line, "%15s", word);
    const char *rest = line + strlen(word);
    if (handleScriptCommand(word, rest)) return;
  }

  const char cmd = toupper((unsigned char)line[0]);
  const char *args = line + 1;

  float f[8];
  long  i[4];
  uint8_t nf, ni;

  switch (cmd) {
    case 'T': {
      nf = parseFloats(args, f, 3);
      if (nf < 2) { Serial.println(F("ERR T 需要 x y")); return; }
      uint32_t ms = nf >= 3 ? (uint32_t)f[2] : DEFAULT_TAP_MS;
      Serial.printf("TAP %.3f %.3f %ums\r\n", f[0], f[1], ms);
      Touch.tap(f[0], f[1], ms);
      break;
    }
    case 'D': {
      nf = parseFloats(args, f, 3);
      if (nf < 2) { Serial.println(F("ERR D 需要 x y")); return; }
      uint32_t gap = nf >= 3 ? (uint32_t)f[2] : 90;
      Serial.printf("DOUBLE %.3f %.3f gap=%ums\r\n", f[0], f[1], gap);
      Touch.doubleTap(f[0], f[1], gap);
      break;
    }
    case 'H': {
      nf = parseFloats(args, f, 3);
      if (nf < 2) { Serial.println(F("ERR H 需要 x y")); return; }
      uint32_t ms = nf >= 3 ? (uint32_t)f[2] : DEFAULT_HOLD_MS;
      Serial.printf("HOLD %.3f %.3f %ums\r\n", f[0], f[1], ms);
      Touch.longPress(f[0], f[1], ms);
      break;
    }
    case 'S': {
      nf = parseFloats(args, f, 5);
      if (nf < 4) { Serial.println(F("ERR S 需要 x1 y1 x2 y2")); return; }
      uint32_t ms = nf >= 5 ? (uint32_t)f[4] : DEFAULT_SWIPE_MS;
      Serial.printf("SWIPE %.3f %.3f -> %.3f %.3f %ums\r\n", f[0], f[1], f[2],
                    f[3], ms);
      Touch.swipe(f[0], f[1], f[2], f[3], ms);
      break;
    }
    case 'C': {
      nf = parseFloats(args, f, 7);
      if (nf < 6) { Serial.println(F("ERR C 需要 x1 y1 cx cy x2 y2")); return; }
      uint32_t ms = nf >= 7 ? (uint32_t)f[6] : DEFAULT_SWIPE_MS;
      Serial.printf("CURVE %.3f %.3f ctrl %.3f %.3f -> %.3f %.3f %ums\r\n",
                    f[0], f[1], f[2], f[3], f[4], f[5], ms);
      Touch.curveSwipe(f[0], f[1], f[2], f[3], f[4], f[5], ms);
      break;
    }
    case 'P': {
      nf = parseFloats(args, f, 9);
      if (nf < 8) { Serial.println(F("ERR P 需要 8 个坐标")); return; }
      uint32_t ms = nf >= 9 ? (uint32_t)f[8] : DEFAULT_SWIPE_MS;
      Serial.printf("2-FINGER %.3f,%.3f->%.3f,%.3f | %.3f,%.3f->%.3f,%.3f %ums\r\n",
                    f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], ms);
      Touch.twoFingerSwipe(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], ms);
      break;
    }
    case 'Z': {
      nf = parseFloats(args, f, 5);
      if (nf < 4) { Serial.println(F("ERR Z 需要 cx cy gap1 gap2")); return; }
      uint32_t ms = nf >= 5 ? (uint32_t)f[4] : DEFAULT_SWIPE_MS;
      Serial.printf("PINCH c=%.3f,%.3f gap %.3f -> %.3f %ums\r\n", f[0], f[1],
                    f[2], f[3], ms);
      Touch.pinch(f[0], f[1], f[2], f[3], ms);
      break;
    }
    case 'M': {
      ni = parseInts(args, i, 2);
      if (ni < 2) { Serial.println(F("ERR M 需要 dx dy")); return; }
      Serial.printf("MOUSE %ld %ld\r\n", i[0], i[1]);
      Touch.mouseMove((int8_t)i[0], (int8_t)i[1]);
      break;
    }
    case 'B': {
      ni = parseInts(args, i, 1);
      long n = ni >= 1 ? i[0] : 1;
      for (long k = 0; k < n; k++) {
        Touch.mouseClick(1);
        delay(80);
      }
      Serial.printf("MOUSE-CLICK x%ld\r\n", n);
      break;
    }
    case 'K': {
      ni = parseInts(args, i, 1);
      if (ni < 1) { Serial.println(F("ERR K 需要 usage")); return; }
      Serial.printf("KEY %ld\r\n", i[0]);
      Touch.keyTap((uint8_t)i[0]);
      break;
    }
    case 'V': {
      ni = parseInts(args, i, 1);
      if (ni < 1) { Serial.println(F("ERR V 需要 usage")); return; }
      Serial.printf("CONSUMER 0x%lX\r\n", i[0]);
      Touch.consumerTap((uint16_t)i[0]);
      break;
    }
    case 'G':
      selfTest();
      break;
    case 'U':
      Touch.releaseAll();
      Serial.println(F("RELEASE-ALL"));
      break;
    case 'R':
      Touch.releaseAll();
      lineLen = 0;
      Serial.println(F("RESET"));
      break;
    case '?':
    case '/':
      printHelp();
      break;
    default:
      Serial.printf("ERR 未知命令 '%c'(? 查看帮助)\r\n", cmd);
      break;
  }
}

static void pollControl() {
  while (Ctrl->available()) {
    int c = Ctrl->read();
    if (c < 0) break;
    if (c == '\r' || c == '\n') {
      if (lineLen > 0) {
        lineBuf[lineLen] = '\0';
        handleLine(lineBuf);
        lineLen = 0;
      }
      continue;
    }
    if (lineLen < sizeof(lineBuf) - 1) {
      lineBuf[lineLen++] = (char)c;
    } else {
      // 行太长,丢弃
      lineLen = 0;
      Serial.println(F("ERR 命令过长"));
    }
  }
}

// ---------------------------------------------------------------------------
//  setup / loop
// ---------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  // UART0 发送缓冲调大:BLE 初始化会占用时间,期间日志容易丢
  Serial.setTxBufferSize(4096);
  Serial.setRxBufferSize(2048);
  delay(300);

#ifdef CONTROL_ON_UART1
  Serial1.begin(115200, SERIAL_8N1, PIN_CTRL_RX, PIN_CTRL_TX);
  Ctrl = &Serial1;
  Serial.println(F("[ctrl] 控制协议已切到 UART1 (TX=GPIO17, RX=GPIO18)"));
#endif

  pinMode(PIN_BOOT_BTN, INPUT_PULLUP);

  // 板载 RGB 灯:上电闪一下(说明固件起来了,也顺便证明灯能用)
  Led::begin();

  Serial.println();
  LOGLN("[boot] ESP32-S3 HID Touch Injector");
  Serial.printf("[boot] Report Map %u 字节, 槽位 %u, 最大触点 %u\n",
                (unsigned)HID_REPORT_MAP_LEN, (unsigned)TOUCH_SLOT_COUNT,
                (unsigned)TOUCH_MAX_CONTACTS);

  if (!Touch.begin()) {
    Serial.println(F("[boot] FATAL: 传输层初始化失败"));
    Serial.println(F("[boot] USB 版请检查 platformio.ini 是否含 ARDUINO_USB_MODE=0"));
    Serial.println(F("[boot] BLE 版请检查 NimBLE 是否正常初始化"));
    while (true) delay(1000);
  }
  LOGF("[boot] HID 就绪(传输层: %s)", Touch.transportName());

  // 脚本引擎:挂载 LittleFS 并扫描 /scripts
  if (!Scripts.begin()) {
    LOGLN("[boot] 脚本引擎不可用,手势命令仍可用");
  } else {
    Scripts.seedExamples();   // 首次使用写入示例脚本(已有则跳过)
    LOGF("[boot] 脚本引擎就绪,共 %u 个脚本", (unsigned)Scripts.count());
  }

  // WiFi + Web 管理(连不上会自动开热点)
  // 脚本等待期间也让网络能应答 —— 否则跑流程时板子会"失联",
  // App 点「编辑流程」必然提示「连接失败」(实测踩过)
  setIdleHook([]() {
    netManager().loop();
    hidTransport().poll();
  });

  // 流程里的 `notify 消息` 步骤 → 报给控制中心(服务器存表 + 可选推 webhook)
  setNotifyHook([](const char *flowName, const char *message) {
    netManager().reportFlowEvent(flowName, message);
  });

  if (netManager().begin()) {
    LOGF("[boot] Web 管理: %s", netManager().url().c_str());
  } else {
    LOGLN("[boot] Web 管理未启动(网络不可用),串口命令不受影响");
  }

  printHelp();

#if EJ_TOUCH_DEMO
  Serial.println(F("[demo] 自动演示手势..."));
  delay(1500);
  selfTest();
  Touch.swipe(0.5f, 0.75f, 0.5f, 0.30f, 260);   // 上滑
  delay(400);
  Touch.swipe(0.5f, 0.30f, 0.5f, 0.75f, 260);   // 下滑
  delay(400);
  Touch.curveSwipe(0.2f, 0.8f, 0.7f, 0.5f, 0.2f, 0.2f, 300);
  delay(400);
  Touch.pinch(0.5f, 0.5f, 0.5f, 0.25f, 300);    // 捏合
  Serial.println(F("[demo] 演示结束"));
#endif
}

void loop() {
  pollControl();

  // Web 请求(非阻塞)
  hidTransport().poll();
  netManager().loop();

  // 执行 Web 端排队的一次性请求 + 定时调度(带进度上报与"停止"支持)
  netServiceScripts();

  // RGB 灯:处理"保持时间到了" + 常态状态色
  Led::tick();
  Led::setStateColor(hidTransport().ready() ? 1 : 0);

  // BOOT 键触发自检(按下沿)
  static bool lastBtn = true;
  bool btn = digitalRead(PIN_BOOT_BTN);
  if (lastBtn && !btn) btnDownAt = millis();
  if (!lastBtn && btn && btnDownAt) {
    uint32_t held = millis() - btnDownAt;
    btnDownAt = 0;
    if (held > 1200) {
      // 长按 = 「找板子」:灯闪起来,一堆同名板子里一眼认出这块
      Serial.println(F("[btn] BOOT 长按 → 找板子模式(灯闪)"));
      Led::identify();
    } else {
      Serial.println(F("[btn] BOOT 按下 → 自检"));
      selfTest();
    }
  }
  lastBtn = btn;

  delay(5);
}
