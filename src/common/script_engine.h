// ============================================================================
//  ScriptEngine —— 通用手势脚本引擎
//
//  定位:把"动作序列"从固件里抽出来,存到 Flash 上,运行时可加载/选择/执行。
//        它是**通用能力**,不含任何具体 App 的坐标或流程 —— 脚本内容由用户提供。
//
//  存储:LittleFS,脚本放在 /scripts/*.txt,一行一个动作。
//  语法:见 README 的"脚本引擎"一节,或对板子发送 ? 查看在线帮助。
// ============================================================================
#pragma once

#include <Arduino.h>

// 单个脚本的最大步骤数
#ifndef SCRIPT_MAX_STEPS
// 单个流程最多多少步。
// ⚠️ 32 步对"ifpage 安全版"不够用 —— 每次点击都要包一层
//    ifpage / else / endif(3 步),11 步的流程会膨胀到 40+ 步。
//    48 步 × 16 个流程 ≈ 75KB,板子还有 120KB+ 空闲,放得下。
#define SCRIPT_MAX_STEPS 48
#endif

// 最多同时载入的脚本数
#ifndef SCRIPT_MAX_SCRIPTS
#define SCRIPT_MAX_SCRIPTS 16
#endif

// 单个脚本名长度上限(含结尾 0)
#ifndef SCRIPT_NAME_LEN
#ifndef STEP_NOTE_LEN
// 单步备注最大长度(含结尾 0)。
// ⚠️ 是**字节**不是字符 —— 中文一个字占 3 字节。
//    原来 40 只够 13 个中文字,用户写说明很容易超。
//    而且超了会被 strncpy 从**字符中间**切断 → 产生非法 UTF-8 →
//    整个 /api/script 的 JSON 解析失败(实测踩过,排查了很久)。
//    现在 72 字节 = 24 个中文字,并且截断会**对齐到字符边界**。
#define STEP_NOTE_LEN 72
#endif

#define SCRIPT_NAME_LEN 48   // 中文名一个字符占 3 字节,留足空间
#endif

// ---------------------------------------------------------------------------
//  步骤类型
// ---------------------------------------------------------------------------
enum class StepOp : uint8_t {
  None = 0,
  Tap,          // T x y [ms]
  DoubleTap,    // D x y [gap]
  Hold,         // H x y [ms]
  Swipe,        // S x1 y1 x2 y2 [ms]
  Curve,        // C x1 y1 cx cy x2 y2 [ms]   终点存扩展字段(8 位精度)
  TwoFinger,    // P x1a y1a x2a y2a x1b y1b x2b y2b [ms]
  Pinch,        // Z cx cy gap1 gap2 [ms]
  Key,          // K usage
  Consumer,     // V usage
  Delay,        // wait ms
  Goto,         // goto label                  跳到标号所在步骤
  Repeat,       // loop n                      从 next 字段处再执行 n 次
  // ---- 页面相关(需要 App 把"当前页面"推给板子)----
  WaitFor,      // waitfor <关键字> [超时ms]    等到页面上出现该关键字
  IfPage,       // ifpage  <关键字>            关键字匹配则继续,否则跳到 else/endif
  Else,         // else                        上一分支执行完 → 跳到 endif
  EndIf,        // endif                       空操作(只作为跳转锚点)
  // ---- 循环 ----
  //   repeat n ... endloop
  //   Repeat.next  = 匹配的 EndLoop 之后(循环结束时从这继续)
  //   EndLoop.next = 配对的 Repeat 下标(还有剩余次数时跳回)
  EndLoop,      // endloop                     标记循环体结束
  //   break —— 立即跳出**当前最内层**循环(跳到该循环 endloop 之后)。
  //   典型用法:轮询里发现了"成功标志"就提前结束,不再白跑剩余次数。
  //     repeat 20
  //       ...点提交...
  //       waitfor 支付宝付款 8000
  //       ifpage 支付宝付款
  //         break 抢到了 别再循环了
  //       endif
  //       ...点继续尝试...
  //     endloop
  Break,        // break [备注]                跳出最内层循环
  //   notify <消息> —— 往控制中心报一个事件(例如"抢到了")。
  //   执行到这一步会 POST 给服务器,服务器存表 + 可选推 webhook。
  //   典型用法:轮询里发现成功标志 → notify + break。
  Notify,       // notify <消息>
};

const char *stepOpName(StepOp op);

// 页面状态(由 App 通过 /api/page 推过来,供 waitfor / ifpage 使用)
void        setPageInfo(const char *activity, const char *text);

/**
 * 「空转回调」—— 脚本等待(wait / waitfor 轮询)期间会**反复调用**它。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要
 *
 *  Scripts.run() 是**同步阻塞**跑完整个流程的。以前 wait 就是裸的
 *  delay(),期间 main loop() 完全停转 → **netManager().loop() 不被调用
 *  → 板子的 WebServer 整个不应答**。
 *
 *  表现:流程一跑,板子就"失联" —— 这时候在 App 里点「编辑流程」
 *        必然超时,提示**「连接失败」**(用户实测反馈)。
 *
 *  修法:等待时穿插调用这个 hook(main.cpp 里设成 netManager().loop()
 *        + hidTransport().poll()),板子在跑流程时也能应答网页请求。
 *
 *  附带好处:**「停止」按钮响应快得多** —— 以前要等当前 wait 走完。
 * ══════════════════════════════════════════════════════════════
 */
typedef void (*IdleHook)();

/**
 * 「通知回调」—— 执行 `notify` 步骤时调用,把事件发到控制中心。
 * 参数:流程名、消息。由 main.cpp 接到 netManager() 上(分层,引擎不直接碰网络)。
 */
typedef void (*NotifyHook)(const char *flowName, const char *message);
void        setNotifyHook(NotifyHook h);
void        setIdleHook(IdleHook h);
const char *pageAct();
const char *pageText();

/**
 * 把 src 拷进 dst(最多 maxBytes 含结尾 0),**保证不切断 UTF-8 字符**。
 *
 * ⚠️ 中文一个字 3 字节,直接 substring/strncpy 会从字符中间切,
 *    产生**非法 UTF-8** → 整个 JSON 响应解析失败。
 *    这个坑在备注、/api/status 的 pageText 上各踩过一次,所以提成公共函数。
 */
void utf8Truncate(char *dst, const char *src, size_t maxBytes);
uint32_t    pageSeenAt();

// ---------------------------------------------------------------------------
//  一个步骤
// ---------------------------------------------------------------------------
struct ScriptStep {
  StepOp  op = StepOp::None;
  float   a = 0, b = 0, c = 0, d = 0;   // 坐标 / 参数(归一化 0..1)
  uint8_t e = 0, f = 0;                 // 扩展坐标(8 位精度)/ usage
  uint32_t ms = 0;                      // 时长(毫秒)
  uint16_t next = 0;                    // Goto: 目标步骤下标
  uint8_t  repeat = 0;                  // Repeat: 剩余次数
  // 备注(可选):写在参数后面的自由文本,例如 "T 0.5 0.5 60 点我的"
  // 用途:日志里用人类语言说明这一步在干什么,而不是一串坐标
  char     note[STEP_NOTE_LEN] = {0};
};

// ---------------------------------------------------------------------------
//  一个脚本
// ---------------------------------------------------------------------------
struct Script {
  char       name[SCRIPT_NAME_LEN] = {0};
  ScriptStep steps[SCRIPT_MAX_STEPS];
  uint8_t    stepCount = 0;
  bool       valid = false;
};

// ---------------------------------------------------------------------------
//  执行结果
// ---------------------------------------------------------------------------
struct ScriptResult {
  bool     ok = false;
  uint16_t executed = 0;
  char     error[64] = {0};
};

// ---------------------------------------------------------------------------
//  引擎
// ---------------------------------------------------------------------------
class ScriptEngine {
public:
  // 挂载 LittleFS 并把 /scripts 下的脚本载入内存
  bool begin();

  bool mounted() const { return _mounted; }
  uint8_t count() const { return _count; }

  const Script *at(uint8_t index) const;
  const Script *find(const char *name) const;

  void forEach(void (*cb)(const Script &, void *ctx), void *ctx) const;

  bool reload();

  // 每步执行前调用;返回 false 表示请求中止(用于 Web 上的"停止")。
  // 参数:当前步序号、总步数
  typedef bool (*StepHook)(uint16_t step, uint16_t total, void *ctx);

  // 执行指定脚本(带 Repeat 步骤时会自动展开)。
  // hook 可以为 nullptr;hook 里可以检查外部停止标志。
  ScriptResult run(uint8_t index, StepHook hook = nullptr, void *hookCtx = nullptr);

  // 中止标志(供 hook 内部查询;run 结束后自动复位)
  void requestStop() { _stopRequested = true; }
  bool stopRequested() const { return _stopRequested; }

  // 是否有流程正在执行(不管从串口/HTTP/BLE/定时哪个入口进来)
  bool running() const { return _running; }

  // 把文本写入 /scripts/<name>.txt 并重新载入;errBuf 返回失败原因
  bool saveFromText(const char *name, const char *text, char *errBuf,
                    size_t errLen);

  bool remove(const char *name);

  // 清空所有脚本文件(恢复出厂用)。兼容 f.name() 返回相对或绝对路径。
  // 返回删除的文件数;-1 表示文件系统未挂载。
  int removeAll();

  // 首次使用(脚本目录为空)时写入几个通用示例脚本。
  // 这些脚本**不会自动执行**,只是让用户有个可参考的起点。
  void seedExamples();

  /**
   * 恢复缺失的示例脚本(只创建**不存在**的,不覆盖已有的)。
   * 用途:示例流程被误删后能一键找回来。
   * @return 实际新建的数量
   */
  int restoreExamples();

  // 解析文本到 Script(不落盘),用于校验
  static bool parse(const char *text, Script &out, char *errBuf, size_t errLen);

  // 只校验"名字 + 正文"能不能保存(不落盘),异步保存入队前先调它
  static bool validate(const char *name, const char *text, char *errBuf, size_t errLen);

private:
  Script  _scripts[SCRIPT_MAX_SCRIPTS];
  uint8_t _count = 0;
  bool    _mounted = false;
  volatile bool _stopRequested = false;
  volatile bool _running = false;   // run() 执行期间为 true
};

extern ScriptEngine Scripts;
