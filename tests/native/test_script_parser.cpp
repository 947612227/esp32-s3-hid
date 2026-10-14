// ============================================================================
//  优化15 流程解析器单元测试(电脑上跑,不需要板子)
//
//  编译的是固件里同一份 src/common/script_parser.cpp。
//  用例大多对应代码注释里写过的"实测踩过"的坑,防止以后改坏。
//
//  用法: bash tests/native/run.sh
// ============================================================================
#include "script_engine.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>

static int g_fail = 0;   // 失败断言数
static int g_pass = 0;   // 通过断言数
static const char *g_case = "";

#define CHECK(cond)                                                          \
  do {                                                                       \
    if (cond) { g_pass++; }                                                  \
    else { g_fail++; printf("  ❌ [%s] 第 %d 行:%s\n", g_case, __LINE__, #cond); } \
  } while (0)

#define NEAR(x, y) (fabsf((float)(x) - (float)(y)) < 1e-4f)

// 解析一段文本;Script 很大(≈12.9KB)放堆上
struct Parsed {
  Script *sc;
  bool ok;
  char err[128];
  explicit Parsed(const char *text) {
    sc = (Script *)calloc(1, sizeof(Script));
    err[0] = '\0';
    ok = ScriptEngine::parse(text, *sc, err, sizeof(err));
  }
  ~Parsed() { free(sc); }
  const ScriptStep &at(int i) const { return sc->steps[i]; }
  bool errHas(const char *s) const { return strstr(err, s) != nullptr; }
};

// 是不是合法 UTF-8(备注截断不能切断中文)
static bool validUtf8(const char *s) {
  const unsigned char *p = (const unsigned char *)s;
  while (*p) {
    int n = (*p < 0x80) ? 0 : ((*p >> 5) == 6) ? 1 : ((*p >> 4) == 14) ? 2 : ((*p >> 3) == 30) ? 3 : -1;
    if (n < 0) return false;
    p++;
    while (n-- > 0) { if ((*p & 0xC0) != 0x80) return false; p++; }
  }
  return true;
}

static std::string readFile(const char *path) {
  FILE *f = fopen(path, "rb");
  if (!f) { printf("  ❌ 打不开 %s\n", path); g_fail++; return ""; }
  std::string s;
  char buf[4096];
  size_t n;
  while ((n = fread(buf, 1, sizeof(buf), f)) > 0) s.append(buf, n);
  fclose(f);
  return s;
}

// ---------------------------------------------------------------------------
static void 坐标写法() {
  g_case = "坐标写法";
  Parsed p("T 50% 0.25 60\nT 16384 32767\nT 150% -0.5\nT 0.5,0.25");
  CHECK(p.ok);
  CHECK(p.at(0).op == StepOp::Tap && NEAR(p.at(0).a, 0.5) && NEAR(p.at(0).b, 0.25) && p.at(0).ms == 60);
  CHECK(NEAR(p.at(1).a, 16384.0f / 32767.0f) && NEAR(p.at(1).b, 1.0f));   // >1 按逻辑坐标
  CHECK(NEAR(p.at(2).a, 1.0f) && NEAR(p.at(2).b, 0.0f));                  // 超范围夹到 0..1
  CHECK(NEAR(p.at(3).a, 0.5) && NEAR(p.at(3).b, 0.25));                   // 逗号也能分隔
}

static void 备注() {
  g_case = "备注";
  Parsed p("T 0.5 0.5 60 点我的\nT 0.5 0.5 点我的\nT 0.5 0.5 60 # 行内注释\nwait 100 稍等 一下");
  CHECK(p.ok);
  CHECK(p.at(0).ms == 60 && strcmp(p.at(0).note, "点我的") == 0);
  CHECK(p.at(1).ms == 0 && strcmp(p.at(1).note, "点我的") == 0);         // 没写 ms 也能带备注
  CHECK(p.at(2).note[0] == '\0');                                         // # 后面是注释,不进备注
  CHECK(p.at(3).ms == 100 && strcmp(p.at(3).note, "稍等 一下") == 0);     // 多个词用一个空格连
}

static void 备注超长按字符截断() {
  g_case = "备注超长按字符截断";
  std::string t = "T 0.5 0.5 ";
  for (int i = 0; i < 30; i++) t += "中";                                  // 90 字节
  Parsed p(t.c_str());
  CHECK(p.ok);
  size_t n = strlen(p.at(0).note);
  CHECK(n <= STEP_NOTE_LEN - 1 && n % 3 == 0 && n > 0);
  CHECK(validUtf8(p.at(0).note));
}

static void waitfor关键字和超时() {
  g_case = "waitfor";
  Parsed p("waitfor 预约想看票档 10000 等预约页出现\nwaitfor 支付宝 付款\nwf @NcovSku 3000");
  CHECK(p.ok);
  CHECK(p.at(0).op == StepOp::WaitFor && p.at(0).ms == 10000 && strcmp(p.at(0).note, "预约想看票档") == 0);
  CHECK(p.at(1).ms == 15000 && strcmp(p.at(1).note, "支付宝 付款") == 0);   // 默认 15 秒,多词关键字
  CHECK(p.at(2).ms == 3000 && strcmp(p.at(2).note, "@NcovSku") == 0);

  Parsed bad("waitfor 5000");
  CHECK(!bad.ok && bad.errHas("缺少关键字"));
}

static void notify整行当消息() {
  g_case = "notify";
  Parsed p("notify 抢到了 快去 付款\n通知 你好 世界");
  CHECK(p.ok);
  CHECK(p.at(0).op == StepOp::Notify && strcmp(p.at(0).note, "抢到了 快去 付款") == 0);
  CHECK(p.at(1).op == StepOp::Notify && strcmp(p.at(1).note, "你好 世界") == 0);
}

static void 嵌套ifpage跳转() {
  g_case = "嵌套 ifpage";
  // 0 ifpage A / 1 else / 2 ifpage B / 3 T / 4 else / 5 T / 6 endif / 7 endif / 8 wait
  Parsed p("ifpage A\nelse\nifpage B\nT 0.1 0.1\nelse\nT 0.2 0.2\nendif\nendif\nwait 1");
  CHECK(p.ok);
  CHECK(p.at(0).next == 2);   // 外层条件不成立 → 外层 else 之后
  CHECK(p.at(1).next == 8);   // 外层 else 走完 → 外层 endif 之后(以前被内层 else 抢走)
  CHECK(p.at(2).next == 5);   // 内层条件不成立 → 内层 else 之后
  CHECK(p.at(4).next == 7);   // 内层 else → 内层 endif 之后
  Parsed q("ifpage A\nT 0.5 0.5\nfi");
  CHECK(q.ok && q.at(0).next == 3);   // 没有 else → endif 之后

  // 内层 if/else/endif 在外层 else **之前**:外层 endif 找 else 时必须跳过内层
  // 0 ifpage A / 1 ifpage B / 2 T / 3 else / 4 T / 5 endif / 6 else / 7 T / 8 endif / 9 wait
  Parsed r("ifpage A\nifpage B\nT 0.1 0.1\nelse\nT 0.2 0.2\nendif\nelse\nT 0.3 0.3\nendif\nwait 1");
  CHECK(r.ok);
  CHECK(r.at(1).next == 4 && r.at(3).next == 6);   // 内层:else 之后 / 内层 endif 之后
  CHECK(r.at(0).next == 7);                        // 外层条件不成立 → 外层 else 之后
  CHECK(r.at(6).next == 9);                        // 外层 else → 外层 endif 之后(以前内层 else 被错指到这)
}

static void 循环配对() {
  g_case = "循环配对";
  Parsed p("repeat 300\nwait 1\nendloop\nloop 2\nwait 1\nendrepeat");
  CHECK(p.ok);
  CHECK(p.at(0).op == StepOp::Repeat && p.at(0).repeat == 300);   // 以前 uint8_t 会截成 44
  CHECK(p.at(0).next == 3 && p.at(2).next == 0);                  // repeat → endloop 之后;endloop → repeat
  CHECK(p.at(3).repeat == 2 && p.at(3).next == 6 && p.at(5).next == 3);

  Parsed max("repeat 65535\nwait 1\nendloop");
  CHECK(max.ok && max.at(0).repeat == 65535);
  Parsed over("repeat 65536\nwait 1\nendloop");
  CHECK(!over.ok && over.errHas("重复次数要在"));
  Parsed orphan("wait 1\nendloop");
  CHECK(!orphan.ok && orphan.errHas("endloop 没有对应的 repeat"));   // 以前静默忽略
  Parsed open("repeat 3\nwait 1");
  CHECK(!open.ok && open.errHas("repeat 没有对应的 endloop"));
}

static void break跳出最内层() {
  g_case = "break";
  // 0 repeat / 1 repeat / 2 break / 3 endloop / 4 break / 5 endloop / 6 wait
  Parsed p("repeat 5\nrepeat 3\nbreak 内层\nendloop\nbreak\nendloop\nwait 1");
  CHECK(p.ok);
  CHECK(p.at(2).next == 4 && strcmp(p.at(2).note, "内层") == 0);   // 内层出口
  CHECK(p.at(4).next == 6);                                         // 外层出口
  Parsed bad("wait 1\nbreak");
  CHECK(!bad.ok && bad.errHas("break 不在任何"));
}

static void 标号和注释() {
  g_case = "标号和注释";
  // 带空格的 # 行是注释,不占标号名额(以前 13 条注释把真标号挤掉了)
  std::string t;
  for (int i = 0; i < 20; i++) t += "# 第 " + std::to_string(i) + " 条 注释\n";
  t += "#终点\nwait 1\ngoto 终点";
  Parsed p(t.c_str());
  CHECK(p.ok);
  CHECK(p.sc->stepCount == 2 && p.at(1).op == StepOp::Goto && p.at(1).next == 0);
  CHECK(strcmp(p.at(1).note, "终点") == 0);   // 标号名存在 note 里,回读才能还原成 goto 终点

  Parsed bad("wait 1\ngoto 无此标号");
  CHECK(!bad.ok && bad.errHas("找不到标号 '#无此标号'"));

  // 标号上限 16 个:第 17 个直接报错(以前静默丢掉)
  std::string m;
  for (int i = 0; i < 16; i++) m += "#L" + std::to_string(i) + "\nwait 1\n";
  Parsed ok16((m + "goto L15").c_str());
  CHECK(ok16.ok);
  Parsed lab17((m + "#L16\nwait 1").c_str());
  CHECK(!lab17.ok && lab17.errHas("标号太多") && lab17.errHas("第 33 行"));

  // goto 上限 16 个:第 17 个报错(以前不登记 ⇒ 静默跳到第 1 步)
  std::string g = "wait 1\n#尾\nwait 1\n";
  for (int i = 0; i < 16; i++) g += "goto 尾\n";
  Parsed g16(g.c_str());
  CHECK(g16.ok && g16.at(17).op == StepOp::Goto && g16.at(17).next == 1);
  Parsed g17((g + "goto 尾").c_str());
  CHECK(!g17.ok && g17.errHas("goto 太多"));
}

static void 错误提示带行号() {
  g_case = "错误提示";
  Parsed a("T 0.5");
  CHECK(!a.ok && a.errHas("第 1 行") && a.errHas("参数不足"));
  Parsed b("wait 1\nT 0.5 abc");
  CHECK(!b.ok && b.errHas("第 2 行") && b.errHas("坐标格式错"));
  Parsed c("wait 1\n\n# 注释\nfoo 1");
  CHECK(!c.ok && c.errHas("第 4 行") && c.errHas("未知指令"));
  Parsed e("# 只有注释\n\n");
  CHECK(!e.ok && e.errHas("没有任何有效步骤"));
}

static void 步数上限() {
  g_case = "步数上限";
  std::string t;
  for (int i = 0; i < SCRIPT_MAX_STEPS; i++) t += "wait 1\n";
  Parsed full(t.c_str());
  CHECK(full.ok && full.sc->stepCount == SCRIPT_MAX_STEPS);
  Parsed over((t + "wait 1").c_str());
  CHECK(!over.ok && over.errHas("步骤太多"));   // 以前超出的步骤被默默丢掉
}

static void 换行和别名() {
  g_case = "换行和别名";
  Parsed p("tap 0.5 0.5 60\r\nW 5\r\nhold 0.5 0.5 900  \r\nswipe 0.5 0.8 0.5 0.2\r\n");
  CHECK(p.ok && p.sc->stepCount == 4);
  CHECK(p.at(0).op == StepOp::Tap && p.at(0).ms == 60 && p.at(0).note[0] == '\0');   // \r 不进备注
  CHECK(p.at(1).op == StepOp::Delay && p.at(1).ms == 5);
  CHECK(p.at(2).op == StepOp::Hold && p.at(2).ms == 900);
  CHECK(p.at(3).op == StepOp::Swipe && p.at(3).ms == 0 && NEAR(p.at(3).d, 0.2));
}

static void 全语法样例(const char *path) {
  g_case = "全语法样例";
  std::string text = readFile(path);
  Parsed p(text.c_str());
  CHECK(p.ok);
  if (!p.ok) { printf("     解析失败:%s\n", p.err); return; }
  CHECK(p.sc->stepCount == 27);
  CHECK(strcmp(p.at(0).note, "点上面") == 0);
  CHECK(NEAR(p.at(2).a, 16384.0f / 32767.0f) && NEAR(p.at(2).b, 20000.0f / 32767.0f));
  CHECK(p.at(4).op == StepOp::Curve && p.at(4).e == 51 && p.at(4).f == 51 && p.at(4).ms == 320);
  CHECK(p.at(5).op == StepOp::TwoFinger && p.at(5).e == 51 && p.at(5).ms == 300);
  CHECK(p.at(7).op == StepOp::Key && p.at(7).e == 40);
  CHECK(p.at(8).op == StepOp::Consumer && p.at(8).e == 233);
  CHECK(p.at(10).ms == 10000 && strcmp(p.at(10).note, "预约想看票档") == 0);
  CHECK(p.at(11).next == 14 && p.at(13).next == 20);   // 外层 ifpage / else
  CHECK(p.at(14).next == 17 && p.at(16).next == 19);   // 内层 ifpage / else
  CHECK(strcmp(p.at(12).note, "抢到了 快去付款") == 0);
  CHECK(p.at(20).repeat == 300 && p.at(20).next == 26 && p.at(25).next == 20);
  CHECK(p.at(22).next == 25);                          // 循环里的 ifpage 没有 else
  CHECK(p.at(23).op == StepOp::Break && p.at(23).next == 26);
  CHECK(p.at(26).op == StepOp::Goto && p.at(26).next == 0);
}

static void 名字校验() {
  g_case = "名字校验";
  char err[128];
  CHECK(!ScriptEngine::validate("", "wait 1", err, sizeof(err)) && strstr(err, "不能为空"));
  std::string longName(SCRIPT_NAME_LEN, 'a');
  CHECK(!ScriptEngine::validate(longName.c_str(), "wait 1", err, sizeof(err)) && strstr(err, "过长"));
  CHECK(!ScriptEngine::validate("a/b", "wait 1", err, sizeof(err)) && strstr(err, "路径分隔符"));
  CHECK(!ScriptEngine::validate("好名字", "foo", err, sizeof(err)) && strstr(err, "未知指令"));
  CHECK(ScriptEngine::validate("好名字", "wait 1", err, sizeof(err)));
}

int main(int argc, char **argv) {
  const char *fixture = argc > 1 ? argv[1] : "tests/native/fixtures/全语法.txt";
  坐标写法();
  备注();
  备注超长按字符截断();
  waitfor关键字和超时();
  notify整行当消息();
  嵌套ifpage跳转();
  循环配对();
  break跳出最内层();
  标号和注释();
  错误提示带行号();
  步数上限();
  换行和别名();
  全语法样例(fixture);
  名字校验();
  printf("\n断言 %d 条:通过 %d,失败 %d\n", g_pass + g_fail, g_pass, g_fail);
  return g_fail ? 1 : 0;
}
