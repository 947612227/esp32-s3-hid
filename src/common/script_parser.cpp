// ============================================================================
//  流程脚本解析(文本 → Script)
//
//  从 script_engine.cpp 拆出来:这里只有纯 C/C++,不依赖 Arduino / LittleFS / HID,
//  固件和电脑上的单元测试(tests/native)编译的是同一份代码。
// ============================================================================
#include "script_engine.h"
#include "touch_limits.h"   // TOUCH_LOGICAL_MAX(裸数字 > 1 按逻辑坐标解释)

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

// ---------------------------------------------------------------------------
//  工具
// ---------------------------------------------------------------------------
void scriptSetErr(char *buf, size_t len, const char *fmt, ...) {
  if (buf == nullptr || len == 0) return;
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, len, fmt, ap);
  va_end(ap);
}

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

// 判断 token 是不是"纯数值"(可带 % 后缀)—— 用来区分"参数"和"备注"
static bool isNumeric(const char *t) {
  if (t == nullptr || *t == '\0') return false;
  char *end = nullptr;
  strtof(t, &end);
  if (end == t) return false;
  if (*end == '%') end++;
  return *end == '\0';
}

// 解析一个参数:支持 "0.5"、"50%"、"16384" 三种写法
//   - 带 % 的按百分比解释
//   - 裸数字 0..1 视为归一化
//   - 裸数字 > 1 视为逻辑坐标 0..32767
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

// 8 位精度的扩展坐标打包(curve 的终点;解包 unpackCoord 在 script_engine.cpp)
static inline uint8_t packCoord(float v) {
  if (v < 0.0f) v = 0.0f;
  if (v > 1.0f) v = 1.0f;
  return (uint8_t)(v * 255.0f + 0.5f);
}

// 把 tok[from..to) 用一个空格连起来存进 dst(超长按 UTF-8 字符边界截断)。
// 一行最多 191 字节,token 之间原来至少隔 1 个字符,拼起来不会比原行长。
static void joinTokens(char *dst, size_t cap, char *tok[], int from, int to) {
  char buf[192];
  size_t len = 0;
  for (int i = from; i < to; i++) {
    if (i > from && len + 1 < sizeof(buf)) buf[len++] = ' ';
    size_t m = strlen(tok[i]);
    if (len + m >= sizeof(buf)) m = sizeof(buf) - 1 - len;
    memcpy(buf + len, tok[i], m);
    len += m;
  }
  buf[len] = '\0';
  utf8Truncate(dst, buf, cap);
}

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

  while (*p && !failed) {
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
        // 超了要报错:以前静默丢掉,跳过去才报"找不到标号",看不出是名额满了
        if (labelCount >= MAX_LABELS) {
          scriptSetErr(errBuf, errLen, "第 %u 行:标号太多(每个流程最多 %u 个)",
                       lineNo, (unsigned)MAX_LABELS);
          failed = true;
          break;
        }
        strncpy(labels[labelCount], name, SCRIPT_NAME_LEN - 1);
        labels[labelCount][SCRIPT_NAME_LEN - 1] = '\0';
        labelStep[labelCount] = out.stepCount;
        labelCount++;
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
      scriptSetErr(errBuf, errLen, "第 %u 行 %s:%s(用法:%s)", lineNo, cmd, msg,
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
      long times = atol(tok[1]);
      if (times < 0 || times > 65535) { fail("重复次数要在 1~65535 之间", "repeat n"); break; }
      st.repeat = (uint16_t)times;
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
      joinTokens(st.note, STEP_NOTE_LEN, tok, 1, kwEnd + 1);
      skipNote = true;

    } else if (!strcasecmp(cmd, "ifpage") || !strcasecmp(cmd, "if")) {
      if (n < 2) { fail("参数不足", "ifpage 关键字"); break; }
      st.op = StepOp::IfPage;
      // 关键字 = 第 1 个 token(后面的 token 忽略,允许写说明)
      utf8Truncate(st.note, tok[1], STEP_NOTE_LEN);
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
      joinTokens(st.note, sizeof(st.note), tok, 1, n);

    } else if (!strcasecmp(cmd, "break") || !strcasecmp(cmd, "跳出")) {
      // break [备注] —— 立即跳出最内层循环
      st.op = StepOp::Break;
      st.next = 0;              // 后处理回填成"该循环的出口"
      if (n >= 2) utf8Truncate(st.note, tok[1], sizeof(st.note));

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
      utf8Truncate(st.note, tok[1], sizeof(st.note));
      // ⚠️ 超了必须报错:以前不登记,回填不到,next 停在 0 ⇒ 这个 goto 会**静默跳到第 1 步**
      if (pendingCount >= MAX_GOTO) { fail("goto 太多(每个流程最多 16 个)", "goto label"); break; }
      pending[pendingCount].step = out.stepCount;
      strncpy(pending[pendingCount].label, tok[1], SCRIPT_NAME_LEN - 1);
      pending[pendingCount].label[SCRIPT_NAME_LEN - 1] = '\0';
      pendingCount++;

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
      if (used < n) joinTokens(st.note, STEP_NOTE_LEN, tok, used, n);
    }

    // ⚠️ 以前循环条件里直接 stepCount < 上限 —— 超出的步骤被**默默丢掉**,保存却显示成功
    if (out.stepCount >= SCRIPT_MAX_STEPS) {
      scriptSetErr(errBuf, errLen, "第 %u 行:步骤太多(每个流程最多 %d 步)", lineNo,
             SCRIPT_MAX_STEPS);
      failed = true;
      break;
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
            scriptSetErr(errBuf, errLen, "第 %u 步 endloop 没有对应的 repeat",
                   (unsigned)(i + 1));
            return false;
          }
        }
      }
      if (sp > 0) {
        scriptSetErr(errBuf, errLen, "repeat 没有对应的 endloop");
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
            scriptSetErr(errBuf, errLen, "第 %u 步 break 不在任何 repeat…endloop 里",
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
      scriptSetErr(errBuf, errLen, "找不到标号 '#%s'", pending[i].label);
      return false;
    }
    out.steps[pending[i].step].next = labelStep[found];
  }

  if (out.stepCount == 0) {
    scriptSetErr(errBuf, errLen, "脚本没有任何有效步骤");
    return false;
  }
  out.valid = true;
  return true;
}

bool ScriptEngine::validate(const char *name, const char *text,
                            char *errBuf, size_t errLen) {
  if (errBuf && errLen) errBuf[0] = '\0';
  if (name == nullptr || *name == '\0') {
    scriptSetErr(errBuf, errLen, "脚本名不能为空"); return false;
  }
  if (strlen(name) >= SCRIPT_NAME_LEN) {
    scriptSetErr(errBuf, errLen, "脚本名过长(最多 %d 字符)", SCRIPT_NAME_LEN - 1);
    return false;
  }
  if (strchr(name, '/') || strchr(name, '\\')) {
    scriptSetErr(errBuf, errLen, "脚本名不能含路径分隔符"); return false;
  }
  // 临时对象放堆上(一个 Script ≈ 12.9KB,栈放不下)
  Script *tmp = (Script *)calloc(1, sizeof(Script));
  if (!tmp) { scriptSetErr(errBuf, errLen, "内存不足"); return false; }
  bool ok = parse(text, *tmp, errBuf, errLen);
  free(tmp);
  return ok;
}
