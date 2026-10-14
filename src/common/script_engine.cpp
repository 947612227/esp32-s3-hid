// ============================================================================
//  ScriptEngine 实现
// ============================================================================
#include "script_engine.h"
#include "led.h"   // 流程开始/结束要改状态灯

#include <LittleFS.h>
#include <esp_heap_caps.h>

#include "hid_touch.h"
#include "log_buffer.h"

ScriptEngine Scripts;

static const char *TAG = "[script]";
static const char *SCRIPT_DIR = "/scripts";

// ---------------------------------------------------------------------------
//  工具
// ---------------------------------------------------------------------------
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
    if (Scripts.stopRequested()) return;      // 等待期间也能被「停止」打断
    // ⚠️ 睡的时间不能超过剩余时间:以前固定 delay(5),
    //    wait 1 实际要 ~6ms(实测 128 步 wait 1 用了 800ms),每个 wait 都多等最多 5ms
    uint32_t el = millis() - t0;
    if (el >= ms) break;
    uint32_t left = ms - el;
    delay(left < 5 ? left : 5);
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

// 8 位精度扩展坐标解包(打包 packCoord 在 script_parser.cpp)
static inline float unpackCoord(uint8_t v) { return (float)v / 255.0f; }

// ---------------------------------------------------------------------------
//  文件系统
// ---------------------------------------------------------------------------
static void repairOldExamples();   // 定义在示例脚本文本之后

// 只做跳转/判断、不产生任何实际动作的步骤(死循环检测用)
static bool isControlOnly(StepOp op) {
  switch (op) {
    case StepOp::Goto:
    case StepOp::Repeat:
    case StepOp::IfPage:
    case StepOp::Else:
    case StepOp::EndIf:
    case StepOp::EndLoop:
    case StepOp::Break:
    case StepOp::None:
      return true;
    default:
      return false;
  }
}

bool ScriptEngine::begin() {
  // 脚本表:优先 PSRAM;没有 PSRAM(或分配失败)退回内部 RAM,容量缩小
  if (_scripts == nullptr) {
    _scripts = (Script *)heap_caps_calloc(SCRIPT_MAX_SCRIPTS, sizeof(Script),
                                          MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (_scripts) {
      _capacity = SCRIPT_MAX_SCRIPTS;
      LOGF("%s 脚本表在 PSRAM:%u 个 × %u 步(%u KB)", TAG, (unsigned)_capacity,
           (unsigned)SCRIPT_MAX_STEPS, (unsigned)(sizeof(Script) * _capacity / 1024));
    } else {
      _scripts = (Script *)heap_caps_calloc(SCRIPT_MAX_SCRIPTS_NO_PSRAM, sizeof(Script),
                                            MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
      if (_scripts) _capacity = SCRIPT_MAX_SCRIPTS_NO_PSRAM;
      LOGF("%s ⚠️ 没有可用 PSRAM,脚本表退回内部 RAM,最多 %u 个流程", TAG,
           (unsigned)_capacity);
    }
    if (_scripts == nullptr) {
      LOGF("%s 脚本表分配失败(内存不足)", TAG);
      return false;
    }
  }

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
  repairOldExamples();
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

  uint8_t overCap = 0;   // 超出容量没载入的文件数
  for (File f = dir.openNextFile(); f; f = dir.openNextFile()) {
    if (f.isDirectory()) continue;

    String fname = f.name();
    if (!fname.endsWith(".txt")) continue;
    // ⚠️ 以前满了就默默不读了 —— 文件在 Flash 上,列表里却看不到
    if (_count >= _capacity) { overCap++; continue; }

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
  if (overCap) {
    LOGF("%s ⚠️ 还有 %u 个流程文件超出容量(最多 %u 个)没有载入", TAG,
         (unsigned)overCap, (unsigned)_capacity);
  }
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

int ScriptEngine::indexOf(const char *name) const {
  if (name == nullptr) return -1;
  for (uint8_t i = 0; i < _count; i++) {
    if (strcasecmp(_scripts[i].name, name) == 0) return i;
  }
  return -1;
}

void ScriptEngine::forEach(void (*cb)(const Script &, void *ctx),
                           void *ctx) const {
  for (uint8_t i = 0; i < _count; i++) cb(_scripts[i], ctx);
}

bool ScriptEngine::saveFromText(const char *name, const char *text,
                                char *errBuf, size_t errLen) {
  if (errBuf && errLen) errBuf[0] = '\0';
  if (!_mounted) { scriptSetErr(errBuf, errLen, "文件系统未挂载"); return false; }
  if (isRunningFlow(name)) {
    scriptSetErr(errBuf, errLen, "「%s」正在执行,先停止再修改", name); return false;
  }
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

  // ⚠️ 这个临时对象必须放**堆**上,不能放栈上。
  //    一个 Script 是 SCRIPT_MAX_STEPS × ~100 字节(128 步 ≈ 12.9KB),
  //    而 loop 任务栈只有 16KB(WebServer 回调就跑在里面)。
  //    实测:48 步(4.9KB)放栈上时 /api/save 就**必然断连**。
  //    calloc ≥4KB 会自动走 PSRAM(CONFIG_SPIRAM_MALLOC_ALWAYSINTERNAL=4096)。
  Script *tmp = (Script *)calloc(1, sizeof(Script));
  if (!tmp) { scriptSetErr(errBuf, errLen, "内存不足"); return false; }
  if (!parse(text, *tmp, errBuf, errLen)) { free(tmp); return false; }   // 先校验
  if (fullFor(name)) {
    scriptSetErr(errBuf, errLen, "流程数已达上限 %u 个,请先删掉不用的", (unsigned)_capacity);
    free(tmp);
    return false;
  }

  String path = String(SCRIPT_DIR) + "/" + name + ".txt";
  File f = LittleFS.open(path, "w");
  if (!f) { scriptSetErr(errBuf, errLen, "写入 %s 失败", path.c_str()); free(tmp); return false; }
  f.print(text);
  f.close();

  reload();
  free(tmp);
  return true;
}

bool ScriptEngine::remove(const char *name) {
  if (!_mounted || name == nullptr) return false;
  if (isRunningFlow(name)) return false;   // 调用方先用 isRunningFlow() 给出明确提示
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
    "# repeat n ... endloop:把中间的步骤整体重复 n 次(1~65535)。\n"
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
    "wait 150\n"
    "endloop\n";

// 旧版固件写进板子的"重复演示"(缺 endloop,解析器会拒绝 ⇒ 每次开机都被跳过)
static const char *EX_REPEAT_OLD =
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

/**
 * 修复旧固件写坏的示例。
 * 只在文件内容和旧示例**一字不差**时才覆盖 —— 用户改过的不动。
 */
static void repairOldExamples() {
  String path = String(SCRIPT_DIR) + "/重复演示.txt";
  if (!LittleFS.exists(path)) return;
  File f = LittleFS.open(path, "r");
  if (!f) return;
  String cur = f.readString();
  f.close();
  if (cur != EX_REPEAT_OLD) return;
  File w = LittleFS.open(path, "w");
  if (!w) return;
  w.print(EX_REPEAT);
  w.close();
  LOGF("%s 已修复旧示例「重复演示」(补上 endloop)", TAG);
}

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
  if (!_mounted || _running) return -1;   // 执行中不许清空
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
    scriptSetErr(res.error, sizeof(res.error), "没有第 %u 个脚本", (unsigned)index);
    return res;
  }
  // 运行标志 + 正在跑的流程名:任何 return 路径都会自动复位
  struct RunningFlag {
    volatile bool &f;
    char *name;
    RunningFlag(volatile bool &x, char *n) : f(x), name(n) { f = true; }
    ~RunningFlag() { f = false; name[0] = '\0'; }
  } runningFlag(_running, _runningName);

  // ⚠️ 执行中会处理蓝牙/网页请求(等待钩子里),删/改**别的**流程会 reload() 重写脚本表,
  //    sc 指向的内容随时会变(甚至变成别的流程)。
  //    所以只在这里读一次 sc,之后全用快照:步数 total、名字 _runningName、步骤副本 steps。
  const uint8_t total = sc->stepCount;
  strncpy(_runningName, sc->name, SCRIPT_NAME_LEN - 1);
  _runningName[SCRIPT_NAME_LEN - 1] = '\0';

  LOGF("%s 执行 [%u] %s (%u 步)", TAG, (unsigned)index, _runningName,
       (unsigned)total);

  // ⚠️⚠️ 状态灯必须在这里点 —— **这是所有流程的唯一必经之路**。
  //     踩过的坑:一开始放在 net_manager 的 netServiceScripts() 里,
  //     但那条路有两个限制:① 要求手机 HID 已连(Touch.ready())
  //     ② 只有 Web/BLE 触发的才走它。
  //     串口 RUN 在 main.cpp 里**直接**调 Scripts.run(),完全绕过去,
  //     表现就是"跑流程灯不变黄"(实测串口日志里根本没有状态色那行)。
  Led::runStarted();
  // ⚠️ 记下开始时刻 —— 流程要是比 5 秒短,灯会把黄色补足到 5 秒,
  //    否则"运行中黄"一闪而过(实测用户根本看不到)。
  const uint32_t ledRunT0 = millis();

  // 执行期复制一份步骤(执行中改写/重载脚本表不影响正在跑的这份)。
  // ⚠️ 放堆上(≥4KB 自动走 PSRAM):128 步 ≈ 12.9KB,放栈上 16KB 的 loop 栈就爆了
  struct StepCopy {
    ScriptStep *p;
    ~StepCopy() { free(p); }
  } copy{(ScriptStep *)malloc(sizeof(ScriptStep) * (total ? total : 1))};
  if (copy.p == nullptr) {
    scriptSetErr(res.error, sizeof(res.error), "内存不足,无法执行");
    return res;
  }
  ScriptStep *steps = copy.p;
  memcpy(steps, sc->steps, sizeof(ScriptStep) * total);
  sc = nullptr;   // 之后禁止再读脚本表(见上面快照说明)
  uint16_t repeatLeft[SCRIPT_MAX_STEPS] = {0};

  uint32_t beginMs = millis();
  uint8_t  pc = 0;
  // 死循环检测:连续多少步都只是跳转/判断(没有点击、等待、通知)。
  // ⚠️ 以前是"总步数 > 1024 就报错" —— repeat 200 × 10 步的正常长循环也会被误杀。
  //    真正要防的是"什么都不干的空转"。
  uint32_t idleSteps = 0;
  static const uint32_t IDLE_STEPS_MAX = 2000;    // 正常流程连续纯跳转不会超过几十步;旧版是总步数 1024 封顶
  uint32_t lastServiceMs = millis();    // 上次让出给网络/蓝牙的时刻
  _stopRequested = false;

  while (pc < total) {
    if (idleSteps > IDLE_STEPS_MAX) {
      scriptSetErr(res.error, sizeof(res.error), "连续 %lu 步没有点击/等待,疑似死循环",
             (unsigned long)IDLE_STEPS_MAX);
      LOGF("%s %s", TAG, res.error);
      return res;
    }
    // 至少每 20ms 让一次网络/蓝牙:只有点击、没有 wait 的循环也能收到「停止」
    if (s_idleHook && millis() - lastServiceMs >= 20) {
      s_idleHook();
      lastServiceMs = millis();
    }
    // 每步前询问外部(Web 的"停止"按钮)
    if (hook != nullptr && !hook((uint16_t)pc, total, hookCtx)) {
      scriptSetErr(res.error, sizeof(res.error), "被用户中止");
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
           (unsigned)total, desc);
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
          if (t >= total) { pc = total; advance = false; break; }
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
        if (loopPc < total && repeatLeft[loopPc] > 1) {
          repeatLeft[loopPc]--;
          pc = (uint8_t)loopPc;                    // 回到 Repeat,再走一遍循环体
          advance = false;
        } else {
          if (loopPc < total) repeatLeft[loopPc] = 0;
        }
        break;
      }

      case StepOp::Notify:
        // 把事件发到控制中心(服务器存表 + 可选推 webhook)。
        // 走 hook 而不是直接调 netManager,是为了保持引擎不依赖网络层。
        if (s_notifyHook) {
          s_notifyHook(_runningName, st.note);
          LOGF("%s   → 已通知服务器:%s", TAG, st.note);
        } else {
          LOGF("%s   → 通知服务器失败:没有接上回调", TAG);
        }
        break;

      case StepOp::Break: {
        // 立即跳出最内层循环:直接跳到那个循环 endloop 之后。
        uint16_t out2 = st.next;
        if (out2 < total) {
          pc = (uint8_t)out2;
        } else {
          pc = total;                      // 循环就在末尾 → 直接结束
        }
        advance = false;
        LOGF("%s   → break:跳出循环,从第 %u 步继续", TAG, (unsigned)(pc + 1));
        break;
      }
      default:
        scriptSetErr(res.error, sizeof(res.error), "第 %u 步类型非法", (unsigned)pc);
        return res;
    }

    if (advance) pc++;
    res.executed++;
    // 按步骤类型判断(不能按耗时:每步都打串口日志,纯跳转也要 ~3ms)
    if (isControlOnly(st.op)) idleSteps++; else idleSteps = 0;
  }

  LOGF("%s 完成:%u 步,耗时 %lu ms", TAG, (unsigned)res.executed,
       (unsigned long)(millis() - beginMs));
  res.ok = true;
  _stopRequested = false;
  // ⚠️ 短流程要让黄色**至少亮满 5 秒** —— 不然一闪而过,用户以为灯没变。
  //    实测:用户跑 1 步的「最近任务」,黄只亮了不到 1 秒。
  //    以前在这里 delay() 补足,整块板子(控制通道/心跳/下一个流程)跟着卡 5 秒;
  //    现在交给 LED 模块在 tick() 里非阻塞地补,run() 立刻返回。
  // 执行结束 → 绿 5 秒(同样放在这里,保证任何入口都走到)
  Led::finished(ledRunT0);

  return res;
}
