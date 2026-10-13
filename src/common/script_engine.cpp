// ============================================================================
//  ScriptEngine 实现
// ============================================================================
#include "script_engine.h"

#include <LittleFS.h>

#include "hid_touch.h"
#include "log_buffer.h"

ScriptEngine Scripts;

static const char *TAG = "[script]";
static const char *SCRIPT_DIR = "/scripts";

// ---------------------------------------------------------------------------
//  工具
// ---------------------------------------------------------------------------
static void setErr(char *buf, size_t len, const char *fmt, ...) {
  if (buf == nullptr || len == 0) return;
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, len, fmt, ap);
  va_end(ap);
}

// 解析一个参数:支持 "0.5"、"50%"、"16384" 三种写法
//   - 带 % 的按百分比解释
//   - 裸数字 0..1 视为归一化
//   - 裸数字 > 1 视为逻辑坐标 0..32767
// 判断 token 是不是"纯数值"(可带 % 后缀)—— 用来区分"参数"和"备注"
// 把一步翻译成人类语言。
//   有备注 → "点击:我的"(需求:优先输出「指令:备注」)
//   无备注 → "点击 (50%, 50%)" 这类坐标描述
// ---------------------------------------------------------------------------
//  当前页面状态(由手机 App 通过 /api/page 推过来)
//
//  为什么绕一圈:脚本在**板子**上跑,但页面识别在**手机 App** 里
//  (无障碍服务)。所以 App 在流程执行期间把"当前页面"推给板子,
//  板子才能在脚本里判断。
// ---------------------------------------------------------------------------
static char s_pageAct[96]  = {0};     // 完整 Activity 类名
static char s_pageText[256] = {0};    // 页面上的可见文字(空格分隔)
static uint32_t s_pageAt = 0;         // 最后更新时间

// ---------------------------------------------------------------- 空转回调
static IdleHook s_idleHook = nullptr;
void setIdleHook(IdleHook h) { s_idleHook = h; }

static NotifyHook s_notifyHook = nullptr;
void setNotifyHook(NotifyHook h) { s_notifyHook = h; }

/**
 * 「带服务的等待」:等 ms 毫秒,期间不断把控制权让给网络/蓝牙。
 *
 * 每 5ms 让一次 —— 足够让 WebServer 及时应答(它的超时是秒级),
 * 又不会明显拖慢脚本节奏。
 */
static void idleWait(uint32_t ms) {
  uint32_t t0 = millis();
  while (millis() - t0 < ms) {
    if (s_idleHook) s_idleHook();
    delay(5);
    if (Scripts.stopRequested()) return;      // 等待期间也能被「停止」打断
  }
}

void setPageInfo(const char *act, const char *text) {
  // ⚠️ 必须用 utf8Truncate,**不能 strncpy** ——
  //    页面文字是中文(3 字节/字),strncpy 会在第 255 字节处
  //    从**字符中间**切断,产生非法 UTF-8,
  //    整个 /api/page 响应解析失败(实测:UnicodeDecodeError)。
  if (act)  utf8Truncate(s_pageAct,  act,  sizeof(s_pageAct));
  if (text) utf8Truncate(s_pageText, text, sizeof(s_pageText));
  s_pageAt = millis();
}

const char *pageAct()  { return s_pageAct; }
uint32_t    pageSeenAt() { return s_pageAt; }

/** 当前页面的可见文字(诊断 + waitfor 关键字参考用) */
const char *pageText()  { return s_pageText; }

/**
 * 关键字匹配。
 *
 * 规则(故意做得宽松,让人好写):
 *   · 关键字以 @ 开头 → 只匹配 Activity 类名
 *   · 否则 → Activity 和页面文字**都找一遍**
 *   · 一律**忽略大小写**,而且是**包含**匹配(不用写全)
 *   · Activity 用短名也能匹配("NcovSkuActivity" 能匹配全类名)
 */
/**
 * 把 src 拷进 dst(最多 maxBytes 含结尾 0),**保证不切断 UTF-8 字符**。
 *
 * ⚠️ 直接用 strncpy 会从字符中间切 —— 中文一个字 3 字节,
 *    切一半就产生非法 UTF-8,整个 JSON 响应都解析不了(实测踩过)。
 */
void utf8Truncate(char *dst, const char *src, size_t maxBytes) {
  if (!dst || maxBytes == 0) return;
  if (!src) { dst[0] = '\0'; return; }
  size_t cap = maxBytes - 1;
  size_t n = strlen(src);
  if (n <= cap) { memcpy(dst, src, n); dst[n] = '\0'; return; }
  size_t k = cap;
  while (k > 0 && ((unsigned char)src[k] & 0xC0) == 0x80) k--;   // 退掉续字节
  memcpy(dst, src, k);
  dst[k] = '\0';
}

static void copyNote(char *dst, const char *src, size_t maxBytes) {
  if (!dst || maxBytes == 0) return;
  if (!src) { dst[0] = '\0'; return; }
  size_t cap = maxBytes - 1;              // 留结尾 0
  size_t n = strlen(src);
  if (n <= cap) { memcpy(dst, src, n); dst[n] = '\0'; return; }
  // 超长 → 往前退到字符边界
  size_t k = cap;
  while (k > 0 && ((unsigned char)src[k] & 0xC0) == 0x80) k--;   // 退掉续字节
  memcpy(dst, src, k);
  dst[k] = '\0';
}

static bool pageContains(const char *kw) {
  if (!kw || !*kw) return true;
  String k(kw);
  k.trim();
  if (k.length() == 0) return true;

  String act(s_pageAct), txt(s_pageText);
  act.toLowerCase(); txt.toLowerCase();

  if (k.charAt(0) == '@') {
    String kk = k.substring(1); kk.toLowerCase();
    return act.indexOf(kk) >= 0;
  }
  String kk = k; kk.toLowerCase();
  return act.indexOf(kk) >= 0 || txt.indexOf(kk) >= 0;
}

static void stepDesc(const ScriptStep &st, char *out, size_t n) {
  const char *act = "步骤";
  switch (st.op) {
    case StepOp::Tap:       act = "点击";     break;
    case StepOp::DoubleTap: act = "双击";     break;
    case StepOp::Hold:      act = "长按";     break;
    case StepOp::Swipe:     act = "滑动";     break;
    case StepOp::Curve:     act = "曲线滑动"; break;
    case StepOp::TwoFinger: act = "双指滑动"; break;
    case StepOp::Pinch:     act = "捏合";     break;
    case StepOp::Key:       act = "按键";     break;
    case StepOp::Consumer:  act = "多媒体键"; break;
    case StepOp::Delay:     act = "等待";     break;
    case StepOp::Repeat:    act = "重复";     break;
    case StepOp::WaitFor:   act = "等待页面"; break;
    case StepOp::IfPage:    act = "如果是页面"; break;
    case StepOp::Else:      act = "否则";     break;
    case StepOp::EndIf:     act = "结束判断"; break;
    case StepOp::Goto:      act = "跳转";     break;
    case StepOp::EndLoop:   act = "循环结束"; break;
    case StepOp::Break:     act = "跳出循环"; break;
    case StepOp::Notify:    act = "通知服务器"; break;
    default: break;
  }
  if (st.note[0] != '\0') {
    snprintf(out, n, "%s:%s", act, st.note);
    return;
  }
  switch (st.op) {
    case StepOp::Delay:
      snprintf(out, n, "等待 %u ms", (unsigned)st.ms);
      break;
    case StepOp::WaitFor:
      snprintf(out, n, "等待出现「%s」(最多 %u ms)", st.note, (unsigned)st.ms);
      break;
    case StepOp::IfPage:
      snprintf(out, n, "如果页面含「%s」", st.note);
      break;
    case StepOp::Else:
      snprintf(out, n, "否则");
      break;
    case StepOp::EndIf:
      snprintf(out, n, "结束判断");
      break;
    case StepOp::Key:
    case StepOp::Consumer:
      snprintf(out, n, "%s 键码 %u", act, (unsigned)st.e);
      break;
    case StepOp::Repeat:
    case StepOp::Goto:
    case StepOp::EndLoop:
      snprintf(out, n, "%s", act);
      break;
    default:
      snprintf(out, n, "%s (%.0f%%, %.0f%%)", act, st.a * 100.0f, st.b * 100.0f);
      break;
  }
}

static bool isNumeric(const char *t) {
  if (t == nullptr || *t == '\0') return false;
  char *end = nullptr;
  strtof(t, &end);
  if (end == t) return false;
  if (*end == '%') end++;
  return *end == '\0';
}

static bool parseNum(const char *tok, float &out) {
  if (tok == nullptr || *tok == '\0') return false;
  char *end = nullptr;
  float v = strtof(tok, &end);
  if (end == tok) return false;

  if (*end == '%') {
    out = v / 100.0f;
  } else if (*end == '\0') {
    out = (v > 1.0f) ? (v / (float)TOUCH_LOGICAL_MAX) : v;
  } else {
    return false;
  }
  if (out < 0.0f) out = 0.0f;
  if (out > 1.0f) out = 1.0f;
  return true;
}

static uint8_t tokenize(char *line, char *tok[], uint8_t maxTok) {
  uint8_t n = 0;
  char *p = line;
  while (*p && n < maxTok) {
    while (*p == ' ' || *p == '\t' || *p == ',') p++;
    if (!*p) break;
    tok[n++] = p;
    while (*p && *p != ' ' && *p != '\t' && *p != ',') p++;
    if (*p) { *p = '\0'; p++; }
  }
  return n;
}

const char *stepOpName(StepOp op) {
  switch (op) {
    case StepOp::Tap:       return "tap";
    case StepOp::DoubleTap: return "double";
    case StepOp::Hold:      return "hold";
    case StepOp::Swipe:     return "swipe";
    case StepOp::Curve:     return "curve";
    case StepOp::TwoFinger: return "twofinger";
    case StepOp::Pinch:     return "pinch";
    case StepOp::Key:       return "key";
    case StepOp::Consumer:  return "consumer";
    case StepOp::Delay:     return "wait";
    case StepOp::Goto:      return "goto";
    case StepOp::Repeat:    return "loop";
    case StepOp::WaitFor:   return "waitfor";
    case StepOp::IfPage:    return "ifpage";
    case StepOp::Else:      return "else";
    case StepOp::EndIf:     return "endif";
    case StepOp::EndLoop:   return "endloop";
    case StepOp::Break:     return "break";
    case StepOp::Notify:    return "notify";
    default:                return "?";
  }
}

// 8 位精度的扩展坐标存取(用于 curve 的终点)
static inline uint8_t packCoord(float v) {
  if (v < 0.0f) v = 0.0f;
  if (v > 1.0f) v = 1.0f;
  return (uint8_t)(v * 255.0f + 0.5f);
}
static inline float unpackCoord(uint8_t v) { return (float)v / 255.0f; }

// ---------------------------------------------------------------------------
//  解析
// ---------------------------------------------------------------------------
bool ScriptEngine::parse(const char *text, Script &out, char *errBuf,
                         size_t errLen) {
  out.stepCount = 0;
  out.valid = false;
  if (errBuf && errLen) errBuf[0] = '\0';

  // 标号(以及 goto)上限。8 太少了 —— 一个带注释的流程很容易超过。
  static const uint8_t MAX_LABELS = 16;
  char     labels[MAX_LABELS][SCRIPT_NAME_LEN];
  uint16_t labelStep[MAX_LABELS];
  uint8_t  labelCount = 0;

  // Goto 需要回填,先把"目标标号名"暂存起来
  struct PendingGoto { uint16_t step; char label[SCRIPT_NAME_LEN]; };
  static const uint8_t MAX_GOTO = 16;
  PendingGoto pending[MAX_GOTO];
  uint8_t pendingCount = 0;

  const char *p = text;
  uint16_t lineNo = 0;
  bool failed = false;
  bool skipNote = false;      // waitfor/ifpage 这类"关键字即备注"的指令用

  while (*p && out.stepCount < SCRIPT_MAX_STEPS && !failed) {
    const char *eol = strchr(p, '\n');
    size_t len = eol ? (size_t)(eol - p) : strlen(p);

    char line[192];
    if (len >= sizeof(line)) len = sizeof(line) - 1;
    memcpy(line, p, len);
    line[len] = '\0';
    p = eol ? eol + 1 : p + strlen(p);
    lineNo++;
    skipNote = false;          // 每条指令独立判断

    // 去掉行尾 \r
    for (size_t i = strlen(line); i > 0 && (line[i-1] == '\r' || line[i-1] == ' '); i--) {
      line[i-1] = '\0';
    }

    // ---- 标号 / 注释 ----
    if (line[0] == '#') {
      char *name = line + 1;
      while (*name == ' ' || *name == '\t') name++;
      // '#if' / '#end' 保留给条件编译,暂不作为标号
      //
      // ⚠️⚠️ 标号名**不能含空格** —— 这是区分"标号"和"注释"的唯一依据。
      //      以前只要 # 开头就登记成标号,于是 `# 三种结果各走各的:` 这种
      //      注释也占一个名额,13 条注释 + 3 个真标号 = 16 个,
      //      而 MAX_LABELS 只有 8 ⇒ **后面的真标号被静默丢弃**,
      //      报错写成"找不到标号 '#结束'",完全看不出是注释挤掉的
      //      (实测:一个带注释的抢票流程死活存不进去)。
      bool isLabel = (*name != '\0');
      if (isLabel) {
        for (const char *q = name; *q; q++) {
          if (*q == ' ' || *q == '\t') { isLabel = false; break; }
        }
      }
      if (isLabel && strncmp(name, "if", 2) != 0 && strncmp(name, "end", 3) != 0) {
        if (labelCount < MAX_LABELS) {
          strncpy(labels[labelCount], name, SCRIPT_NAME_LEN - 1);
          labels[labelCount][SCRIPT_NAME_LEN - 1] = '\0';
          labelStep[labelCount] = out.stepCount;
          labelCount++;
        }
      }
      continue;   // 注释/标号都不产生步骤
    }

    // 行内注释
    char *hash = strchr(line, '#');
    if (hash) *hash = '\0';

    char *tok[12];
    uint8_t n = tokenize(line, tok, 12);
    if (n == 0) continue;

    ScriptStep st;
    const char *cmd = tok[0];

    auto fail = [&](const char *msg, const char *usage) {
      setErr(errBuf, errLen, "第 %u 行 %s:%s(用法:%s)", lineNo, cmd, msg,
             usage ? usage : "");
      failed = true;
    };

    if (!strcasecmp(cmd, "T") || !strcasecmp(cmd, "tap")) {
      if (n < 3) { fail("参数不足", "T x y [ms]"); break; }
      st.op = StepOp::Tap;
      if (!parseNum(tok[1], st.a) || !parseNum(tok[2], st.b)) {
        fail("坐标格式错", "T 0.5 0.5 | T 50% 50%");
        break;
      }
      st.ms = (n >= 4) ? (uint32_t)atol(tok[3]) : 0;

    } else if (!strcasecmp(cmd, "D") || !strcasecmp(cmd, "double")) {
      if (n < 3) { fail("参数不足", "D x y [gap]"); break; }
      st.op = StepOp::DoubleTap;
      if (!parseNum(tok[1], st.a) || !parseNum(tok[2], st.b)) {
        fail("坐标格式错", "D 0.5 0.5");
        break;
      }
      st.ms = (n >= 4) ? (uint32_t)atol(tok[3]) : 0;

    } else if (!strcasecmp(cmd, "H") || !strcasecmp(cmd, "hold")) {
      if (n < 3) { fail("参数不足", "H x y [ms]"); break; }
      st.op = StepOp::Hold;
      if (!parseNum(tok[1], st.a) || !parseNum(tok[2], st.b)) {
        fail("坐标格式错", "H 0.5 0.5 900");
        break;
      }
      st.ms = (n >= 4) ? (uint32_t)atol(tok[3]) : 0;

    } else if (!strcasecmp(cmd, "S") || !strcasecmp(cmd, "swipe")) {
      if (n < 5) { fail("参数不足", "S x1 y1 x2 y2 [ms]"); break; }
      st.op = StepOp::Swipe;
      if (!parseNum(tok[1], st.a) || !parseNum(tok[2], st.b) ||
          !parseNum(tok[3], st.c) || !parseNum(tok[4], st.d)) {
        fail("坐标格式错", "S 0.5 0.8 0.5 0.2 300");
        break;
      }
      st.ms = (n >= 6) ? (uint32_t)atol(tok[5]) : 0;

    } else if (!strcasecmp(cmd, "C") || !strcasecmp(cmd, "curve")) {
      // 6 个坐标:起点(a,b)、控制点(c,d)、终点(e,f 8 位精度)
      if (n < 7) { fail("参数不足", "C x1 y1 cx cy x2 y2 [ms]"); break; }
      st.op = StepOp::Curve;
      float ex = 0, ey = 0;
      if (!parseNum(tok[1], st.a) || !parseNum(tok[2], st.b) ||
          !parseNum(tok[3], st.c) || !parseNum(tok[4], st.d) ||
          !parseNum(tok[5], ex) || !parseNum(tok[6], ey)) {
        fail("坐标格式错", "C 0.2 0.8 0.7 0.5 0.2 0.2 320");
        break;
      }
      st.e = packCoord(ex);
      st.f = packCoord(ey);
      st.ms = (n >= 8) ? (uint32_t)atol(tok[7]) : 0;

    } else if (!strcasecmp(cmd, "P") || !strcasecmp(cmd, "twofinger")) {
      // 8 个坐标:两指起点 + 两指终点。用 a,b 存第一指起点,
      // c,d 存第一指终点;第二指用额外两个 float —— 结构体只有 4 个,
      // 所以这里限制为"两指平行滑动 + 起点间距",即:
      //   P x1 y1 x2 y2 [gap] [ms]   两根手指水平相距 gap 同时从 y1 滑到 y2
      if (n < 5) { fail("参数不足", "P x1 y1 x2 y2 [gap] [ms]"); break; }
      st.op = StepOp::TwoFinger;
      if (!parseNum(tok[1], st.a) || !parseNum(tok[2], st.b) ||
          !parseNum(tok[3], st.c) || !parseNum(tok[4], st.d)) {
        fail("坐标格式错", "P 0.5 0.7 0.5 0.3 0.2 300");
        break;
      }
      st.e = (n >= 6) ? packCoord(strtof(tok[5], nullptr)) : 51;  // 默认间距 0.2
      st.ms = (n >= 7) ? (uint32_t)atol(tok[6]) : 0;

    } else if (!strcasecmp(cmd, "Z") || !strcasecmp(cmd, "pinch")) {
      if (n < 5) { fail("参数不足", "Z cx cy gap1 gap2 [ms]"); break; }
      st.op = StepOp::Pinch;
      if (!parseNum(tok[1], st.a) || !parseNum(tok[2], st.b) ||
          !parseNum(tok[3], st.c) || !parseNum(tok[4], st.d)) {
        fail("坐标格式错", "Z 0.5 0.5 0.6 0.2 300");
        break;
      }
      st.ms = (n >= 6) ? (uint32_t)atol(tok[5]) : 0;

    } else if (!strcasecmp(cmd, "K") || !strcasecmp(cmd, "key")) {
      if (n < 2) { fail("参数不足", "K usage"); break; }
      st.op = StepOp::Key;
      st.e = (uint8_t)atol(tok[1]);

    } else if (!strcasecmp(cmd, "V") || !strcasecmp(cmd, "consumer")) {
      if (n < 2) { fail("参数不足", "V usage"); break; }
      st.op = StepOp::Consumer;
      st.e = (uint8_t)atol(tok[1]);

    } else if (!strcasecmp(cmd, "wait") || !strcasecmp(cmd, "W")) {
      if (n < 2) { fail("参数不足", "wait ms"); break; }
      st.op = StepOp::Delay;
      st.ms = (uint32_t)atol(tok[1]);

    } else if (!strcasecmp(cmd, "repeat") || !strcasecmp(cmd, "loop")) {
      if (n < 2) { fail("参数不足", "repeat n"); break; }
      st.op = StepOp::Repeat;
      st.repeat = (uint8_t)atol(tok[1]);
      st.next = out.stepCount;   // 回填:重复时跳回自身

    } else if (!strcasecmp(cmd, "waitfor") || !strcasecmp(cmd, "wf")) {
      // waitfor <关键字> [超时ms]
      //   关键字写 Activity(可带 @ 前缀)或页面上的文字都行。
      //   关键字的匹配是"包含",不用写全。
      if (n < 2) { fail("参数不足", "waitfor 关键字 [超时ms]"); break; }
      st.op = StepOp::WaitFor;
      st.ms = 15000;                       // 默认最多等 15 秒
      // 从左往右找**第一个纯数字 token** 当超时:
      //     waitfor 预约想看票档 10000 等预约页出现
      //             └── 关键字 ──┘ └超时┘ └── 备注(忽略)──┘
      //
      // ⚠️ 以前只认「行尾的数字」—— 数字后面一写备注就找不到超时,
      //    整行被当成关键字,于是永远匹配不上、还静默用默认 15 秒(实测踩过)。
      int numAt = -1;
      for (int i = 1; i < n; i++) {
        if (isNumeric(tok[i])) { numAt = i; break; }
      }
      int kwEnd = (numAt < 0) ? (n - 1) : (numAt - 1);
      if (numAt >= 0) st.ms = (uint32_t)atol(tok[numAt]);
      if (kwEnd < 1) { fail("缺少关键字", "waitfor 关键字 [超时ms]"); break; }
      // ⚠️ 关键字就存在 note 字段里 —— 这两个指令"关键字即描述",
      //    没有再单独写备注的必要,也省了 ScriptStep 的空间。
      String kw;
      for (int i = 1; i <= kwEnd; i++) { if (i > 1) kw += ' '; kw += tok[i]; }
      copyNote(st.note, kw.c_str(), STEP_NOTE_LEN);
      skipNote = true;

    } else if (!strcasecmp(cmd, "ifpage") || !strcasecmp(cmd, "if")) {
      if (n < 2) { fail("参数不足", "ifpage 关键字"); break; }
      st.op = StepOp::IfPage;
      // 关键字 = 第 1 个 token(后面的 token 忽略,允许写说明)
      copyNote(st.note, tok[1], STEP_NOTE_LEN);
      skipNote = true;

    } else if (!strcasecmp(cmd, "else")) {
      st.op = StepOp::Else;
      skipNote = true;

    } else if (!strcasecmp(cmd, "notify") || !strcasecmp(cmd, "通知")) {
      // notify <消息> —— 往控制中心发一个事件
      if (n < 2) { fail("参数不足", "notify 消息内容"); break; }
      st.op = StepOp::Notify;
      // ⚠️ notify 要**整行**当消息,不能只取第一个词 ——
      //    和 waitfor 不一样(waitfor 是"关键字 + 超时 + 备注"),
      //    notify 后面写的全是给人看的话,只取首个词会丢内容。
      {
        String msg;
        for (int k = 1; k < n; k++) {
          if (msg.length()) msg += " ";
          msg += tok[k];
        }
        copyNote(st.note, msg.c_str(), sizeof(st.note));
      }

    } else if (!strcasecmp(cmd, "break") || !strcasecmp(cmd, "跳出")) {
      // break [备注] —— 立即跳出最内层循环
      st.op = StepOp::Break;
      st.next = 0;              // 后处理回填成"该循环的出口"
      if (n >= 2) copyNote(st.note, tok[1], sizeof(st.note));

    } else if (!strcasecmp(cmd, "endloop") || !strcasecmp(cmd, "endrepeat")) {
      st.op = StepOp::EndLoop;
      st.next = 0;              // 后处理回填成配对的 repeat 下标
      skipNote = true;

    } else if (!strcasecmp(cmd, "endif") || !strcasecmp(cmd, "fi")) {
      st.op = StepOp::EndIf;
      skipNote = true;

    } else if (!strcasecmp(cmd, "goto")) {
      if (n < 2) { fail("参数不足", "goto label"); break; }
      st.op = StepOp::Goto;
      // ⚠️ 标号名存进 note —— 回读(scriptToText)要靠它还原成 `goto 标号`。
      //    以前只存在临时的 pending[] 里,回读时只能写成
      //    "# (goto 跳转到第 N 步)" 这种注释 ⇒ **一编辑流程 goto 就全丢了**
      //    (实测:带 goto 的流程读回来变成一堆注释,标号也不见了)。
      copyNote(st.note, tok[1], sizeof(st.note));
      if (pendingCount < MAX_GOTO) {
        pending[pendingCount].step = out.stepCount;
        strncpy(pending[pendingCount].label, tok[1], SCRIPT_NAME_LEN - 1);
        pending[pendingCount].label[SCRIPT_NAME_LEN - 1] = '\0';
        pendingCount++;
      }

    } else {
      fail("未知指令", "T/D/H/S/C/P/Z/K/V/wait/repeat/goto");
      break;
    }

    // ---- 备注(可选):已知参数之后的自由文本 ----
    //   例:"T 0.5 0.5 60 点我的"      → 参数 0.5/0.5/60,备注 "点我的"
    //       "T 0.5 0.5 点我的"          → 参数 0.5/0.5,备注 "点我的"(没写 ms)
    //   规则:从第一个**非数值** token 开始,后面全部当备注。
    //   goto 特例:它的第一个参数本身就是文字(标号)。
    if (!skipNote) {
      int used = 1;
      if (!strcasecmp(cmd, "goto")) {
        used = (n >= 2) ? 2 : 1;
      } else {
        while (used < n && isNumeric(tok[used])) used++;
      }
      if (used < n) {
        String noteStr;
        for (int i = used; i < n; i++) {
          if (i > used) noteStr += ' ';
          noteStr += tok[i];
        }
        copyNote(st.note, noteStr.c_str(), STEP_NOTE_LEN);
      }
    }

    out.steps[out.stepCount] = st;
    out.stepCount++;
  }

  if (failed) return false;

  // ---- 回填 ifpage / else 的跳转目标 ----
  //   IfPage.next = 条件**不成立**时跳到哪(else 之后 或 endif 之后)
  //   Else.next   = 走到 else 时跳到哪(endif 之后)
  //   用一个栈做配对,支持嵌套。
  {
    int stack[16];
    int sp = 0;
    for (uint8_t i = 0; i < out.stepCount; i++) {
      StepOp op = out.steps[i].op;
      if (op == StepOp::IfPage) {
        if (sp < 16) stack[sp++] = i;
      } else if (op == StepOp::Else) {
        if (sp > 0) {
          int ifIdx = stack[sp - 1];
          out.steps[ifIdx].next = (uint16_t)(i + 1);   // 条件不成立 → 从 else 后面继续
        }
      } else if (op == StepOp::EndIf) {
        if (sp > 0) {
          int ifIdx = stack[--sp];
          if (out.steps[ifIdx].next == 0) {           // 没有 else
            out.steps[ifIdx].next = (uint16_t)(i + 1);
          }
          // 找这对 if/endif 之间的 else,让它跳到 endif 之后
          // ⚠️ 必须**跳过嵌套的 if...endif** ——
          //    否则外层的 endif 会找到**内层**的 else,
          //    把它错指到外层之后(嵌套时内层 else 的分支会被整段吃掉)。
          {
            int depth = 0;
            for (uint8_t k = (uint8_t)(ifIdx + 1); k < i; k++) {
              StepOp o = out.steps[k].op;
              if (o == StepOp::IfPage) depth++;
              else if (o == StepOp::EndIf) depth--;
              else if (o == StepOp::Else && depth == 0) {
                out.steps[k].next = (uint16_t)(i + 1);
                break;
              }
            }
          }
        }
      }
    }
  }

    // ---- 回填 repeat / endloop 的配对 ----
    //   Repeat.next  = 匹配的 EndLoop **之后**(循环跑完后从这继续)
    //   EndLoop.next = 配对的 Repeat 下标(还有剩余次数时跳回去)
    //
    // ⚠️ 之前的实现是 `st.next = 自己`,执行时 `pc = st.next` 又跳回自己
    //    且 advance=false ⇒ **死循环卡在 repeat 这一步**,
    //    循环体一次都不执行、日志被刷满(实测确认)。
    {
      int stack[16];
      int sp = 0;
      for (uint8_t i = 0; i < out.stepCount; i++) {
        StepOp op = out.steps[i].op;
        if (op == StepOp::Repeat) {
          if (sp < 16) stack[sp++] = i;
        } else if (op == StepOp::EndLoop) {
          if (sp > 0) {
            int repIdx = stack[--sp];
            out.steps[repIdx].next = (uint16_t)(i + 1);
            out.steps[i].next = (uint16_t)repIdx;
          } else {
            // ⚠️ 孤立的 endloop 必须报错。
            //    以前是**静默忽略** —— 用户打错字或多写一行时,
            //    保存成功但循环根本没生效,只能看到"怎么没循环"却查不出原因。
            setErr(errBuf, errLen, "第 %u 步 endloop 没有对应的 repeat",
                   (unsigned)(i + 1));
            return false;
          }
        }
      }
      if (sp > 0) {
        setErr(errBuf, errLen, "repeat 没有对应的 endloop");
        return false;
      }
    }

    // ---- 回填 break:跳到**最内层**循环的出口 ----
    //   必须放在 repeat/endloop 配对**之后** ——
    //   因为 break 要用到 Repeat.next(循环过后从哪继续)。
    {
      int stack[16];
      int sp = 0;
      for (uint8_t i = 0; i < out.stepCount; i++) {
        StepOp op = out.steps[i].op;
        if (op == StepOp::Repeat) {
          if (sp < 16) stack[sp++] = i;
        } else if (op == StepOp::EndLoop) {
          if (sp > 0) sp--;
        } else if (op == StepOp::Break) {
          if (sp == 0) {
            setErr(errBuf, errLen, "第 %u 步 break 不在任何 repeat…endloop 里",
                   (unsigned)(i + 1));
            return false;
          }
          out.steps[i].next = out.steps[stack[sp - 1]].next;   // 该循环的出口
        }
      }
    }


  // ---- 回填 goto 目标 ----
  for (uint8_t i = 0; i < pendingCount; i++) {
    int found = -1;
    for (uint8_t j = 0; j < labelCount; j++) {
      if (strcasecmp(labels[j], pending[i].label) == 0) { found = j; break; }
    }
    if (found < 0) {
      setErr(errBuf, errLen, "找不到标号 '#%s'", pending[i].label);
      return false;
    }
    out.steps[pending[i].step].next = labelStep[found];
  }

  if (out.stepCount == 0) {
    setErr(errBuf, errLen, "脚本没有任何有效步骤");
    return false;
  }
  out.valid = true;
  return true;
}

// ---------------------------------------------------------------------------
//  文件系统
// ---------------------------------------------------------------------------
bool ScriptEngine::begin() {
  // 先尝试挂载(不自动格式化)。空白分区首次挂载会失败,这时显式格式化再挂载。
  // 之前用 begin(true) 会在空白分区上"看似成功但容量为 0",所以改成显式流程。
  _mounted = LittleFS.begin();
  uint32_t total = _mounted ? LittleFS.totalBytes() : 0;
  Serial.printf("%s mount=%d total=%u\n", TAG, (int)_mounted, (unsigned)total);

  if (!_mounted || total < 4096) {
    Serial.printf("%s 首次使用:格式化 LittleFS 分区...\n", TAG);
    LittleFS.end();
    bool fmt = LittleFS.format();
    Serial.printf("%s format=%d\n", TAG, (int)fmt);
    if (!fmt) {
      Serial.printf("%s 格式化失败 —— 分区表里可能没有 spiffs 分区\n", TAG);
      _mounted = false;
      return false;
    }
    _mounted = LittleFS.begin();
    total = _mounted ? LittleFS.totalBytes() : 0;
    Serial.printf("%s 格式化后 mount=%d total=%u\n", TAG, (int)_mounted,
                  (unsigned)total);
  }

  if (!_mounted || total < 4096) {
    Serial.printf("%s LittleFS 挂载失败(容量 %u 字节)\n", TAG,
                  (unsigned)total);
    _mounted = false;
    return false;
  }
  Serial.printf("%s LittleFS 就绪:分区 %u KB,已用 %u KB\n", TAG,
                (unsigned)(LittleFS.totalBytes() / 1024),
                (unsigned)(LittleFS.usedBytes() / 1024));
  if (LittleFS.totalBytes() < 4096) {
    Serial.println(F("[script] !! LittleFS 分区容量为 0 —— 分区表没生效"));
    Serial.println(F("[script] !! 请执行 pio run -t uploadfs 或检查 partitions_8mb.csv"));
    _mounted = false;
    return false;
  }
  if (!LittleFS.exists(SCRIPT_DIR)) {
    LittleFS.mkdir(SCRIPT_DIR);
  }
  reload();
  return true;
}

bool ScriptEngine::reload() {
  _count = 0;
  if (!_mounted) return false;

  File dir = LittleFS.open(SCRIPT_DIR);
  if (!dir || !dir.isDirectory()) {
    Serial.printf("%s 目录 %s 打不开\n", TAG, SCRIPT_DIR);
    return false;
  }

  for (File f = dir.openNextFile(); f && _count < SCRIPT_MAX_SCRIPTS;
       f = dir.openNextFile()) {
    if (f.isDirectory()) continue;

    String fname = f.name();
    if (!fname.endsWith(".txt")) continue;

    int slash = fname.lastIndexOf('/');
    String base = (slash >= 0) ? fname.substring(slash + 1) : fname;
    base.replace(".txt", "");   // 统一取文件名部分作为脚本名

    Script &sc = _scripts[_count];
    memset(&sc, 0, sizeof(sc));
    strncpy(sc.name, base.c_str(), sizeof(sc.name) - 1);

    String content = f.readString();
    char err[64] = {0};
    if (parse(content.c_str(), sc, err, sizeof(err))) {
      _count++;
      Serial.printf("%s 载入 %-16s %u 步\n", TAG, sc.name,
                    (unsigned)sc.stepCount);
    } else {
      Serial.printf("%s 跳过 %s:%s\n", TAG, sc.name, err);
    }
  }
  dir.close();
  LOGF("%s 可用脚本 %u 个", TAG, (unsigned)_count);
  return true;
}

const Script *ScriptEngine::at(uint8_t index) const {
  if (index >= _count) return nullptr;
  return &_scripts[index];
}

const Script *ScriptEngine::find(const char *name) const {
  if (name == nullptr) return nullptr;
  for (uint8_t i = 0; i < _count; i++) {
    if (strcasecmp(_scripts[i].name, name) == 0) return &_scripts[i];
  }
  return nullptr;
}

void ScriptEngine::forEach(void (*cb)(const Script &, void *ctx),
                           void *ctx) const {
  for (uint8_t i = 0; i < _count; i++) cb(_scripts[i], ctx);
}

bool ScriptEngine::saveFromText(const char *name, const char *text,
                                char *errBuf, size_t errLen) {
  if (errBuf && errLen) errBuf[0] = '\0';
  if (!_mounted) { setErr(errBuf, errLen, "文件系统未挂载"); return false; }
  if (name == nullptr || *name == '\0') {
    setErr(errBuf, errLen, "脚本名不能为空"); return false;
  }
  if (strlen(name) >= SCRIPT_NAME_LEN) {
    setErr(errBuf, errLen, "脚本名过长(最多 %d 字符)", SCRIPT_NAME_LEN - 1);
    return false;
  }
  if (strchr(name, '/') || strchr(name, '\\')) {
    setErr(errBuf, errLen, "脚本名不能含路径分隔符"); return false;
  }

  // ⚠️ 这个临时对象必须放**堆**上,不能放栈上。
  //    一个 Script 是 SCRIPT_MAX_STEPS × ~100 字节 —— 48 步就是 4.9KB,
  //    而 Arduino 的 loop 任务栈默认只有 8KB(WebServer 回调就跑在里面)。
  //    实测:改成 48 步之后 /api/save **必然断连**,其它 POST 却都正常。
  Script *tmp = (Script *)calloc(1, sizeof(Script));
  if (!tmp) { setErr(errBuf, errLen, "内存不足"); return false; }
  if (!parse(text, *tmp, errBuf, errLen)) { free(tmp); return false; }   // 先校验

  String path = String(SCRIPT_DIR) + "/" + name + ".txt";
  File f = LittleFS.open(path, "w");
  if (!f) { setErr(errBuf, errLen, "写入 %s 失败", path.c_str()); free(tmp); return false; }
  f.print(text);
  f.close();

  reload();
  free(tmp);
  return true;
}

bool ScriptEngine::remove(const char *name) {
  if (!_mounted || name == nullptr) return false;
  String path = String(SCRIPT_DIR) + "/" + name + ".txt";
  if (!LittleFS.exists(path)) return false;
  LittleFS.remove(path);
  reload();
  return true;
}


// ---------------------------------------------------------------------------
//  首次使用的示例脚本(全中文:文件名、注释、说明都是中文)
//
//  注意:文件名用中文是可以的 —— LittleFS 支持 UTF-8 文件名,
//        网页浏览器也会自动做 URL 编码,所以网页上能正常显示和操作。
//        只有串口命令里用中文名时,要注意终端编码(建议用网页操作)。
// ---------------------------------------------------------------------------
static const char *EX_NAV_HOME =
    "# 回桌面\n"
    "# 点一下底部中间的方块键就够了,不需要先按返回。\n"
    "T 0.50 0.963 60\n";

static const char *EX_NAV_BACK =
    "# 返回上一级 / 关掉弹窗\n"
    "# 点底部右侧的向左箭头。需要退几级就重复几行。\n"
    "T 0.78 0.963 60\n";

static const char *EX_NAV_RECENTS =
    "# 打开最近任务(多任务界面)\n"
    "T 0.22 0.963 60\n";

static const char *EX_DISMISS_HOME =
    "# 先退几级,再回桌面\n"
    "# 用途:屏幕上有弹窗、或停在很深的子页面时,先逐级退出再回桌面。\n"
    "# 底部三键坐标(三星 1080x2316 实测):\n"
    "#   最近任务 0.22,0.963 | 回桌面 0.50,0.963 | 返回 0.78,0.963\n"
    "T 0.78 0.963 60\n"     // 返回
    "wait 400\n"
    "T 0.78 0.963 60\n"     // 再退一级
    "wait 400\n"
    "T 0.50 0.963 60\n";    // 回桌面

static const char *EX_SCROLL_DOWN =
    "# 向下滚动三屏\n"
    "# 从屏幕下方滑到上方 = 内容往下滚。\n"
    "S 0.5 0.75 0.5 0.25 260\n"
    "wait 600\n"
    "S 0.5 0.75 0.5 0.25 260\n"
    "wait 600\n"
    "S 0.5 0.75 0.5 0.25 260\n"
    "wait 400\n";

static const char *EX_REPEAT =
    "# 演示 repeat 的用法\n"
    "# repeat n 表示:把它后面的所有步骤整体重复 n 次。\n"
    "T 0.30 0.50 60\n"
    "wait 200\n"
    "T 0.50 0.50 60\n"
    "wait 200\n"
    "T 0.70 0.50 60\n"
    "wait 300\n"
    "repeat 3\n"
    "T 0.50 0.30 60\n"
    "wait 150\n"
    "T 0.50 0.70 60\n"
    "wait 150\n";

static const char *EX_CURVE =
    "# 曲线滑动 + 双指捏合\n"
    "# C 是二次贝塞尔:C 起点x 起点y 控制点x 控制点y 终点x 终点y 时长\n"
    "C 0.20 0.80 0.70 0.50 0.20 0.20 320\n"
    "wait 500\n"
    "# Z 是捏合/张开:Z 中心x 中心y 起始间距 结束间距 时长\n"
    "Z 0.50 0.50 0.55 0.20 300\n";

// 示例脚本清单(seedExamples 和 restoreExamples 共用)
struct ExScript { const char *name; const char *text; };

static const ExScript *exampleList(size_t &n) {
  static const ExScript ex[] = {
      {"回桌面",           EX_NAV_HOME},
      {"返回",             EX_NAV_BACK},
      {"最近任务",         EX_NAV_RECENTS},
      {"逐级返回后回桌面", EX_DISMISS_HOME},
      {"向下滚动",         EX_SCROLL_DOWN},
      {"重复演示",         EX_REPEAT},
      {"曲线滑动",         EX_CURVE},
  };
  n = sizeof(ex) / sizeof(ex[0]);
  return ex;
}

int ScriptEngine::restoreExamples() {
  if (!_mounted) return 0;
  size_t n = 0;
  const ExScript *ex = exampleList(n);
  int created = 0;
  for (size_t i = 0; i < n; i++) {
    String path = String(SCRIPT_DIR) + "/" + ex[i].name + ".txt";
    if (LittleFS.exists(path)) continue;      // 已存在就跳过,不覆盖
    File f = LittleFS.open(path, "w");
    if (!f) continue;
    f.print(ex[i].text);
    f.close();
    created++;
    LOGF("%s 恢复示例脚本 %s", TAG, ex[i].name);
  }
  if (created) reload();
  return created;
}

void ScriptEngine::seedExamples() {
  if (!_mounted) return;
  if (_count > 0) return;   // 已经有脚本就不动

  struct { const char *name; const char *text; } examples[] = {
      {"回桌面",           EX_NAV_HOME},
      {"返回",             EX_NAV_BACK},
      {"最近任务",         EX_NAV_RECENTS},
      {"逐级返回后回桌面", EX_DISMISS_HOME},
      {"向下滚动",         EX_SCROLL_DOWN},
      {"重复演示",         EX_REPEAT},
      {"曲线滑动",         EX_CURVE},
  };


  Serial.printf("%s 首次使用:写入 %u 个示例脚本\n", TAG,
                (unsigned)(sizeof(examples) / sizeof(examples[0])));

  for (auto &ex : examples) {
    String path = String(SCRIPT_DIR) + "/" + ex.name + ".txt";
    File f = LittleFS.open(path, "w");
    if (!f) {
      Serial.printf("%s 写入 %s 失败\n", TAG, ex.name);
      continue;
    }
    f.print(ex.text);
    f.close();
  }
  reload();
}

// 把 File::name() 的结果规范化成绝对路径
// 不同 Arduino-ESP32 版本里 name() 可能返回 "a.txt" 或 "/scripts/a.txt"
static String toAbsScriptPath(const char *rawName) {
  String n(rawName);
  if (n.startsWith("/")) return n;
  if (n.startsWith("scripts/")) return String("/") + n;
  return String(SCRIPT_DIR) + "/" + n;
}

int ScriptEngine::removeAll() {
  if (!_mounted) return -1;
  int removed = 0;
  File dir = LittleFS.open(SCRIPT_DIR);
  if (!dir || !dir.isDirectory()) return 0;

  // 先收集路径,再删除(避免边遍历边删导致的迭代器问题)
  String paths[SCRIPT_MAX_SCRIPTS * 2];
  int n = 0;
  for (File f = dir.openNextFile();
       f && n < (int)(sizeof(paths) / sizeof(paths[0]));
       f = dir.openNextFile()) {
    if (f.isDirectory()) continue;
    String p = toAbsScriptPath(f.name());
    if (p.endsWith(".txt")) paths[n++] = p;
  }
  dir.close();

  for (int i = 0; i < n; i++) {
    if (LittleFS.remove(paths[i])) {
      removed++;
    } else {
      Serial.printf("%s 删除失败: %s\n", TAG, paths[i].c_str());
    }
  }
  reload();
  LOGF("%s 已清空脚本:%d 个", TAG, removed);
  return removed;
}

// ---------------------------------------------------------------------------
//  执行
// ---------------------------------------------------------------------------
ScriptResult ScriptEngine::run(uint8_t index, StepHook hook,
                               void *hookCtx) {
  ScriptResult res;
  const Script *sc = at(index);
  if (sc == nullptr) {
    setErr(res.error, sizeof(res.error), "没有第 %u 个脚本", (unsigned)index);
    return res;
  }

  LOGF("%s 执行 [%u] %s (%u 步)", TAG, (unsigned)index, sc->name,
       (unsigned)sc->stepCount);

  // Repeat 步骤需要在执行期保存剩余次数,所以复制一份步骤到栈上/局部
  ScriptStep steps[SCRIPT_MAX_STEPS];
  memcpy(steps, sc->steps, sizeof(ScriptStep) * sc->stepCount);
  uint8_t repeatLeft[SCRIPT_MAX_STEPS] = {0};

  uint32_t beginMs = millis();
  uint8_t  pc = 0;
  uint16_t guard = 0;
  _stopRequested = false;

  while (pc < sc->stepCount) {
    if (++guard > 1024) {
      setErr(res.error, sizeof(res.error), "步骤执行次数过多,疑似死循环");
      return res;
    }
    // 每步前询问外部(Web 的"停止"按钮)
    if (hook != nullptr && !hook((uint16_t)pc, sc->stepCount, hookCtx)) {
      setErr(res.error, sizeof(res.error), "被用户中止");
      LOGF("%s 已中止于第 %u 步", TAG, (unsigned)pc);
      _stopRequested = false;
      res.ok = false;
      return res;
    }

    ScriptStep &st = steps[pc];
    bool advance = true;

    // 逐步日志:把指令翻译成人类语言(有备注用备注,没有就报坐标)
    {
      char desc[STEP_NOTE_LEN + 24];
      stepDesc(st, desc, sizeof(desc));
      LOGF("%s [%u/%u] %s", TAG, (unsigned)(pc + 1),
           (unsigned)sc->stepCount, desc);
    }

    switch (st.op) {
      case StepOp::Tap:
        Touch.tap(st.a, st.b, st.ms ? st.ms : DEFAULT_TAP_MS);
        break;
      case StepOp::DoubleTap:
        Touch.doubleTap(st.a, st.b, st.ms ? st.ms : 90);
        break;
      case StepOp::Hold:
        Touch.longPress(st.a, st.b, st.ms ? st.ms : DEFAULT_HOLD_MS);
        break;
      case StepOp::Swipe:
        Touch.swipe(st.a, st.b, st.c, st.d, st.ms ? st.ms : DEFAULT_SWIPE_MS);
        break;
      case StepOp::Curve:
        // 终点在扩展字段里(8 位精度)
        Touch.curveSwipe(st.a, st.b, st.c, st.d, unpackCoord(st.e),
                         unpackCoord(st.f), st.ms ? st.ms : DEFAULT_SWIPE_MS);
        break;
      case StepOp::TwoFinger: {
        // 两指水平相距 gap,从 (x1,y1) 滑到 (x2,y2)
        float gap = unpackCoord(st.e);
        Touch.twoFingerSwipe(st.a - gap / 2.0f, st.b, st.c - gap / 2.0f, st.d,
                             st.a + gap / 2.0f, st.b, st.c + gap / 2.0f, st.d,
                             st.ms ? st.ms : DEFAULT_SWIPE_MS);
        break;
      }
      case StepOp::Pinch:
        Touch.pinch(st.a, st.b, st.c, st.d, st.ms ? st.ms : DEFAULT_SWIPE_MS);
        break;
      case StepOp::Key:
        Touch.keyTap(st.e);
        break;
      case StepOp::Consumer:
        Touch.consumerTap(st.e);
        break;
      case StepOp::Delay:
        idleWait(st.ms);   // 不只是 delay —— 期间要服务网络
        break;
      case StepOp::Goto:
        pc = (uint8_t)st.next;
        advance = false;
        break;

      // ---- 页面相关 ----
      case StepOp::WaitFor: {
        // 等到页面上出现关键字。**超时就继续往下走**(不报错)——
        // 因为流程设计者可能只是"希望"等到,而不是"必须"等到。
        uint32_t t0 = millis();
        uint32_t limit = st.ms ? st.ms : 15000;
        bool ok = false;
        while (millis() - t0 < limit) {
          if (pageContains(st.note)) { ok = true; break; }
          idleWait(120);   // 同上:轮询页面时也让网络能动
          // 等待期间也要能响应"停止"
          if (_stopRequested) break;
        }
        // ⚠️ 分两种写,让日志**自己说明为什么超时** ——
        //    用户会问"超时是等待时间不够吗",答案得能从日志看出来。
        if (ok) {
          LOGF("%s   → 页面已出现(等了 %u ms,当前页面:%s)", TAG,
               (unsigned)(millis() - t0), s_pageAct[0] ? s_pageAct : "(未知)");
        } else {
          LOGF("%s   → 超时(等了 %u ms 没等到「%s」)", TAG,
               (unsigned)(millis() - t0), st.note);
          if (s_pageAct[0] == 0 && s_pageText[0] == 0) {
            LOGF("%s     ⚠️ 当时**没拿到任何页面信息** —— 不是关键字的问题,"
                 "是页面识别没在推(看 App 的「页面识别」是不是 ✅ 已开启)", TAG);
          } else {
            LOGF("%s     当时页面:%s", TAG, s_pageAct[0] ? s_pageAct : "(未知)");
            char tb[110];
            utf8Truncate(tb, s_pageText, sizeof(tb));
            LOGF("%s     那页上的词:%s", TAG, tb);
            LOGF("%s     ↑ 从这些词里挑一个(或用悬浮窗 🔍 挑带 ⭐ 的)"
                 "才能等到;现在这个关键字那页上没有", TAG);
          }
          LOGF("%s     超时**不算失败**,下一步照常走", TAG);
        }
        break;
      }

      case StepOp::IfPage:
        if (!pageContains(st.note)) {
          // 条件不成立 → 跳到 else 之后 / endif 之后
          uint16_t t = st.next;
          if (t >= sc->stepCount) { pc = sc->stepCount; advance = false; break; }
          pc = (uint8_t)t;
          advance = false;
          LOGF("%s   → 不匹配,跳到第 %u 步", TAG, (unsigned)(t + 1));
        } else {
          LOGF("%s   → 匹配,继续", TAG);
        }
        break;

      case StepOp::Else:
        // 走到 else 说明上面那支已经执行完了 → 跳过 else 分支
        pc = (uint8_t)st.next;
        advance = false;
        break;

      case StepOp::EndIf:
        // 空操作,只是跳转锚点
        break;
      case StepOp::Repeat:
        // 首次进入 → 记下次数,然后**往下走进循环体**(advance 保持 true)。
        // 回跳进来时 repeatLeft 已经减过,不会被重置。
        if (repeatLeft[pc] == 0) repeatLeft[pc] = (st.repeat > 0 ? st.repeat : 1);
        break;

      case StepOp::EndLoop: {
        // 循环体跑完一轮。
        uint16_t loopPc = st.next;                 // 配对的 Repeat 下标
        if (loopPc < sc->stepCount && repeatLeft[loopPc] > 1) {
          repeatLeft[loopPc]--;
          pc = (uint8_t)loopPc;                    // 回到 Repeat,再走一遍循环体
          advance = false;
        } else {
          if (loopPc < sc->stepCount) repeatLeft[loopPc] = 0;
        }
        break;
      }

      case StepOp::Notify:
        // 把事件发到控制中心(服务器存表 + 可选推 webhook)。
        // 走 hook 而不是直接调 netManager,是为了保持引擎不依赖网络层。
        if (s_notifyHook) {
          s_notifyHook(sc->name, st.note);
          LOGF("%s   → 已通知服务器:%s", TAG, st.note);
        } else {
          LOGF("%s   → 通知服务器失败:没有接上回调", TAG);
        }
        break;

      case StepOp::Break: {
        // 立即跳出最内层循环:直接跳到那个循环 endloop 之后。
        uint16_t out2 = st.next;
        if (out2 < sc->stepCount) {
          pc = (uint8_t)out2;
        } else {
          pc = sc->stepCount;                      // 循环就在末尾 → 直接结束
        }
        advance = false;
        LOGF("%s   → break:跳出循环,从第 %u 步继续", TAG, (unsigned)(pc + 1));
        break;
      }
      default:
        setErr(res.error, sizeof(res.error), "第 %u 步类型非法", (unsigned)pc);
        return res;
    }

    if (advance) pc++;
    res.executed++;
  }

  LOGF("%s 完成:%u 步,耗时 %lu ms", TAG, (unsigned)res.executed,
       (unsigned long)(millis() - beginMs));
  res.ok = true;
  _stopRequested = false;
  return res;
}
