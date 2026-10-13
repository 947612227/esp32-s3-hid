# waitfor 排查全程:一个 NPE 让整个页面识别失效

**结论:`waitfor` 现在完全可用。** 但为了让它能用,挖出了 4 个问题,其中 1 个是**致命的隐藏崩溃**。

---

## 一开始的现象

```
板子 page = ''      ← 从来没收到过页面
enabled_accessibility_services = null
waitfor → 永远超时
```

---

## 问题 ①:无障碍服务根本没开

MIUI 不让用 `settings put` 开(`会被还原`),只能走 UI。

**而且 MIUI 拦截触摸点击** —— `input tap` 点在开关上完全没反应。

**解法:用按键事件**(MIUI 没拦):

```bash
for i in 1 2 3; do adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 0.5; done
adb shell input keyevent KEYCODE_DPAD_CENTER
```

---

## 问题 ②:waitfor 解析器只认「行尾的数字」

```
waitfor 不存在的页面 3000 等一个永远
                     ↑ 数字后面写了备注 → 找不到超时
```

结果:关键字变成整串 `不存在的页面 3000 等一个永远` → **永远匹配不上**,
而且静默用默认的 15 秒。

**改法**:从左往右找**第一个纯数字**当超时,前面是关键字,后面是备注(忽略)。

```
waitfor 预约想看票档 10000 等预约页出现
        └── 关键字 ──┘ └超时┘ └── 备注(忽略)──┘
```

---

## 问题 ③(致命):`pushPage` 传了 null 回调 → App 每 700ms 崩一次

```java
public void pushPage(String act, String text) {
    ...
    post(u, null);      // ← null 回调
}
```

`Board` 的 HTTP 回调里:

```java
ui.post(new Runnable() {
    @Override public void run() { cb.done(fok, fres); }   // ← NPE!
});
```

**logcat 的 crash buffer 里:**

```
FATAL EXCEPTION: main
java.lang.NullPointerException: Attempt to invoke interface method
  'void com.dsh.touchpad.Board$Cb.done(boolean, java.lang.String)'
  on a null object reference
    at com.dsh.touchpad.Board$1$1.run(Board.java:112)
```

紧接着:

```
MIUILOG- Reject RestartService packageName :com.dsh.touchpad
```

### 这一个 bug 造成了三个错觉

| 我看到的 | 我当时的判断 | 真相 |
|---|---|---|
| 切到大麦后 App 就没了 | "MIUI 把它杀了" | **是自己崩的** |
| 无障碍绑定卡死 | "MIUI 的 bug,只能重启" | **App 崩了 → MIUI 拒绝重启它** |
| `waitfor` 不工作 | "无障碍没开" | **页面推送一执行就崩** |

**排查了很久才想到去看 `logcat -b crash`。**
之前一直在看 `logcat` 的默认 buffer,里面只有 MIUI 的噪音日志。

### 修法(双重保险)

```java
// ① Board 里防御
if (cb == null) return;

// ② pushPage 给个空回调,**不要传 null**
public static final Cb NOOP = new Cb() {
    @Override public void done(boolean ok, String body) { }
};
...
post(u, NOOP);
```

---

## 问题 ④:Android 10 后台进程会被回收 → 必须前台服务

修了 NPE 之后 App 不崩了,但切到后台仍可能被系统回收。
而**跑流程时目标 App 在前台、我们的 App 在后台** —— 页面识别就断了。

**加了前台服务**(带一条常驻通知):

```
状态栏:触摸注入板 · 页面识别
        别划掉这条通知,划掉后系统可能杀掉后台,
        页面识别和定时就会停
```

### ⚠️ 这里踩过一个鸡生蛋

```java
// ❌ 错:用「已连上」判断要不要起前台服务
boolean needAlive = boardBusy || PageWatcher.enabled();

// ✅ 对:用「设置里开关开着」判断
boolean needAlive = boardBusy || PageWatcher.enabledInSettings(ctx);
```

用 `enabled()`(已连上)会死循环:
**App 被杀 → sConnected=false → 不起前台服务 → 进程保不住 → 永远连不上。**

所以 `PageWatcher` 现在有两个方法,语义必须分清:

| 方法 | 含义 |
|---|---|
| `enabled()` | **真的连上了**(能读页面) |
| `enabledInSettings()` | 设置里**开关开着**(不代表连上) |

界面上也据此做了**三态显示**:

```
✅ 已开启 · cn.damai/homepage.MainActivity     ← 正常
⚠️ 开关是开的,但没连上 —— 重启手机才能恢复      ← 卡死
❌ 未开启 —— 点这里去开启                       ← 没开
```

---

## 最终验证

### 后台存活 + 页面实时更新

```
+ 3s  App进程=1  板子page=android.widget.FrameLayout
+ 6s  App进程=1  板子page=cn.damai.launcher.splash.SplashMainActivity
+12s  App进程=1  板子page=cn.damai.homepage.MainActivity
+30s  App进程=1  板子page=cn.damai.homepage.MainActivity

前台服务还在? 出现 2
崩溃次数 0
```

### waitfor 匹配分支

```
[1/2] 等待页面:homepage
  → 页面已出现(等了 0 ms,当前页面:cn.damai.homepage.MainActivity)
[2/2] 点击:首页到了
完成:2 步,耗时 103 ms
```

### waitfor 超时分支

```
[1/2] 等待页面:完全不存在的页面
  → 超时,继续执行(等了 4080 ms,
     当前页面:cn.damai.trade.newtradeorder...ProjectDetailActivity)
[2/2] 点击:超时了也继续
完成:2 步,耗时 4180 ms
```

**注意超时日志里的「当前页面」是真实的大麦订单页** —— 说明 App 在持续推页面 ✅

---

## 给用户的 MIUI 配置(仍需手动)

前台服务能大幅降低被杀概率,但 **MIUI 的「自启动」权限仍然必需**:

| # | 设置项 | 路径 |
|---|---|---|
| ① | **自启动** | 设置 → 应用设置 → 应用管理 → 触摸注入板 → 自启动 → 允许 |
| ② | **省电策略** | 同上 → 省电策略 → 无限制 |
| ③ | **锁定后台** | 最近任务 → 把卡片下拉加锁 |

App 里 **菜单 → ❓ 帮助 → 🛡 App 老是被杀** 有完整说明。

> ⚠️ **重装 APK 之后一定要重启手机** —— MIUI 会把无障碍绑定弄坏。

---

## 教训

### 1. 崩溃要看 `logcat -b crash`

默认 buffer 里全是系统噪音,**crash buffer 才是真相**。

```bash
adb logcat -d -b crash | tail -30
```

### 2. 回调一定要判空

`post(url, null)` 这种写法很自然,但**回调里一解引用就炸**。
要么判空,要么传一个明确的 `NOOP`。

### 3. 别急着怪系统

我一开始把三个现象都归因于"MIUI 杀后台 / MIUI 的 bug",
还写了文档说"只能重启手机"。**实际上全是自己代码崩的。**

**先看崩溃日志,再怀疑系统。**

### 4. 「开关开着」和「真的连上」是两回事

尤其是无障碍这种**系统持有服务实例**的场景,
必须分开判断,否则很容易写出鸡生蛋的逻辑。
