# waitfor 演示流程

板子上有两个新流程:

| 流程名 | 作用 |
|---|---|
| **`waitfor演示`** | **安全演示** —— 只等待观察,不点危险地方,可随时运行 |
| **`大麦小米8-waitfor版`** | 你真实流程的 waitfor 版(小米8 坐标) |

---

## 怎么玩 `waitfor演示`

1. 把大麦切到**任意页面**(首页 / 详情 / 票档都行)
2. 运行 `waitfor演示`
3. 打开 **日志** 页看 —— 每一步都会告诉你:
   - 当前**实际**是哪个页面
   - 是"立刻匹配" / "等了一会" / "超时"

**运行中你手动切大麦的页面,能看到后面几步立刻通过。**

### 流程内容

```
wait 1  本流程只等待观察不点危险地方可随时运行
wait 1  打开日志页看每一步的结果
waitfor homepage 15000          等大麦首页(开屏广告也等)
ifpage homepage
wait 1  分支A 现在在首页
else
wait 1  分支B 现在不在首页
endif
waitfor ProjectDetail 10000     等演出详情页(短名就行)
waitfor NcovSkuActivity 10000   等预约票档页(抢票关键页)
waitfor 立即预订 5000            纯文字关键字也能匹配
waitfor @cn.damai 5000          加 @ 前缀 = 只匹配 Activity
wait 300  演示结束
```

### 实测日志(大麦停在开屏页时)

```
[3/13] 等待页面:homepage
  → 超时,继续执行(等了 15119 ms,当前页面:cn.damai.launcher.splash.SplashMainActivity)
[7/13] 等待:分支B 现在不在首页                    ← else 分支走对了
[9/13] 等待页面:ProjectDetail  → 超时
[10/13] 等待页面:NcovSkuActivity → 超时
[11/13] 等待页面:立即预订      → 超时
[12/13] 等待页面:@cn.damai
  → 页面已出现(等了 0 ms,当前页面:cn.damai.launcher.splash.SplashMainActivity)  ← 匹配!
完成:11 步,耗时 40698 ms
```

**一次运行同时演示了**:超时分支 / 匹配分支 / else 分支 / 实时页面显示。

---

## `大麦小米8-waitfor版`(真实流程)

把原来的 `wait 3000` 换成 `waitfor`,并在每步外面加了 ifpage 保护:

```
waitfor homepage 15000      等大麦首页
ifpage homepage
T 0.919 0.908 30            点右下角"我的"
else
wait 1  不在首页跳过避免点错      ← 页面不对就**不点**,避免点错地方
endif
waitfor 我的 8000           等"我的"页
ifpage 我的
T 0.639 0.403 30            点列表第一项
else
wait 1  没进我的跳过
endif
...
waitfor NcovSkuActivity 10000   等预约票档页
ifpage NcovSkuActivity
T 0.622 0.912 40            点"立即预订"
else
wait 1  不是票档页跳过
endif
waitfor 确认 8000
T 0.583 0.911 40            点确认
```

**核心思路**:

| 原来 | 现在 |
|---|---|
| `wait 3000` 写死等 3 秒 | `waitfor 关键字 8000` 等到页面真的出现 |
| 页面慢了 → 点空 | 页面没到就等,最多等 8 秒 |
| 页面不对 → **点到别的地方** | `ifpage` 判断,**不对就跳过,绝不乱点** |

> ⚠️ **关键字可能和实际不一样** —— 请这样核对:
> 把大麦切到那一页 → 悬浮窗点 **🔍 复制页面句柄** → 替换 `waitfor` 后面的字。

---

# 🐛 写 demo 时挖出的两个严重 bug

## bug ① `scriptToText()` 少了 4 个分支 → **编辑流程会毁掉 waitfor**

板子里有**两个**序列化函数:

| 函数 | 用途 |
|---|---|
| `stepToText()` | **日志显示** |
| **`scriptToText()`** | **存回文件 / 给 App 编辑** |

当初只给**显示用的**那个加了 WaitFor/IfPage/Else/EndIf 分支,
**存盘用的那个漏了**。

### 后果(实测)

写进去:
```
waitfor homepage 15000
ifpage homepage
T 0.919 0.908 30 点右下角我的
else
...
endif
```

读回来:
```
# 未知步骤 homepage          ← waitfor 没了!
# 未知步骤 homepage
wait 100 【分支A】现在在首页
# 未知步骤
...
```

**App 读取流程时拿到 `# 未知步骤`,你一编辑一保存,整个 waitfor/ifpage 逻辑就全丢了。**

板子**执行**没问题(解析器是好的)—— 坏的只有序列化。

### 修法

给 `scriptToText()` 补上 4 个分支:

```cpp
case StepOp::WaitFor:
  snprintf(line, sizeof(line), "waitfor %s %u", st.note,
           (unsigned)(st.ms ? st.ms : 15000)); break;
case StepOp::IfPage:
  snprintf(line, sizeof(line), "ifpage %s", st.note); break;
case StepOp::Else:
  snprintf(line, sizeof(line), "else"); break;
case StepOp::EndIf:
  snprintf(line, sizeof(line), "endif"); break;
```

**还有一个坑**:`waitfor` / `ifpage` 的 `note` 字段装的是**关键字**,
而 `scriptToText` 结尾会无条件把 note 附加上去 →
会输出 `waitfor homepage homepage`(关键字就错了)。

所以要跳过:

```cpp
bool noteShown = (st.op == StepOp::WaitFor || st.op == StepOp::IfPage);
if (!noteShown && st.note[0] != '\0') { out += " "; out += st.note; }
```

---

## bug ② 备注截断**切断了 UTF-8** → 整个 JSON 失效

`STEP_NOTE_LEN = 40` 是**字节**数 —— 中文一个字占 3 字节,**只能放 13 个字**。

用户写长一点的说明就超,`strncpy` 会从**字符中间**切断:

```
UnicodeDecodeError: 'utf-8' codec can't decode bytes in position 119-120:
  invalid continuation byte
```

**整个 `/api/script` 响应都解析不了** —— 不只是备注坏了,是**读流程直接失败**。

### 修法

**① 长度 40 → 72**(24 个中文字),内存够(加约 16KB,板子还有 140KB)

**② 截断对齐到字符边界**:

```cpp
static void copyNote(char *dst, const char *src, size_t maxBytes) {
  size_t cap = maxBytes - 1;
  size_t n = strlen(src);
  if (n <= cap) { memcpy(dst, src, n); dst[n] = '\0'; return; }
  size_t k = cap;
  while (k > 0 && ((unsigned char)src[k] & 0xC0) == 0x80) k--;  // 退掉续字节
  memcpy(dst, src, k);
  dst[k] = '\0';
}
```

三处 `strncpy(st.note, ...)` 全部换成 `copyNote()`。

---

# ⚠️ 两个已知限制

### ① 注释行(`#` 开头)不会被保存

解析器**直接跳过**注释行,不存进结构体,所以 `scriptToText` 也输出不出来。

**后果**:你在流程里写的大段 `# 说明`,**一编辑就没了**。

**变通办法**:用 **`wait 1 说明文字`** 当注释 ——
备注是**会持久化**的,而且 `wait 1` 只等 1 毫秒,没有任何副作用。

```
wait 1  本流程只等待观察不点危险地方可随时运行
wait 1  打开日志页看每一步的结果
waitfor homepage 15000
```

日志里会显示 `[1/13] 等待:本流程只等待观察不点危险地方可随时运行` ✅

### ② 单个流程最多 32 步(含 ifpage 的分支步骤)

> **已过时**:脚本表搬到 PSRAM 后上限是每个流程 128 步、最多 32 个流程,
> 超出会在保存时报错,不再截断(见 README「脚本引擎」一节)。下面是当时的记录。

`SCRIPT_MAX_STEPS = 32`。

**注意**:`ifpage / else / endif` **每个都算一步**!
上面那个真实流程 27 步,已经接近上限了。

超过 32 步的部分会被截断 —— 写长流程时留意。

> 内存允许的话可以调大(每个脚本约 3.3KB,16 个脚本共约 52KB);
> 现在板子还有 140KB 空闲,需要的话可以提到 48。
