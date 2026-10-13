# 触摸注入板 · 手机 App(APK)

ESP32-S3 板子的**手机端主控台**。通过局域网 HTTP 与板子通信。

> ## 📖 完整用法看 [`../docs/使用说明.md`](../docs/使用说明.md)
>
> 本文只记录 **App 自己的实现细节和踩过的坑**。

## 它现在能做什么

比网页控制台**强得多** —— 网页管不了的它都能管:

| 能力 | 网页控制台 | 手机 App |
|---|---|---|
| 看状态 / 跑流程 / 改脚本 | ✅ | ✅ |
| **坐标拾取**(在真实界面点) | ❌ | ✅ |
| **悬浮窗**(用时也能看日志/倒计时) | ❌ | ✅ |
| **页面识别**(知道现在在哪个页面) | ❌ | ✅ |
| **定时 / 频率**(带时长控制) | ❌ | ✅ |
| **可视化编排**(卡片 + 拖滑块) | ❌ | ✅ |
| **自动发现板子**(不用记 IP) | ❌ | ✅ |
| 流程复制 / 过期管理 / 启动提醒 | ❌ | ✅ |

> **为什么不把网页做得更强**:很多能力(悬浮窗、无障碍、跨 App 计时)
> 天然只有**原生 App** 能做,浏览器沙箱里做不到。

## 界面

| 页签 | 能做什么 |
|---|---|
**页签在顶部**(不在底部)—— 因为系统三键导航栏会盖住屏幕最底部,放底部会点不到。

### 界面布局踩过的三个坑

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 页签被系统三键挡住,**点不到** | 页签放在屏幕底部,而那一段被系统导航栏占据 | 页签挪到**顶部** |
| 2 | 挪到顶部后,**标题被状态栏盖住、页签文字看不见** | Android 15(API 35)起默认 **edge-to-edge**,内容会画到状态栏下面 | 加 `values-v35` 主题设 `windowOptOutEdgeToEdgeEnforcement`,并手动留出状态栏高度 |
| 3 | 顶部出现**两条标题栏**(蓝色 + 黑色) | 主题继承了系统 ActionBar,和自绘顶栏重复 | 主题改成 `Theme.Material.NoActionBar` |

> 另外 `build.sh` 里加了 `--auto-add-overlay`(覆盖 android 命名空间资源时需要)。

| **状态** | 网络模式/IP、HID 是否就绪、空闲内存、运行时长、流程数、执行次数、上次结果与错误;定时状态;停止脚本 / 抬起触点 / 重启 / 恢复出厂 |
| **流程** | 列出所有流程(含每步明细);运行 / 循环 5 次 / 编辑 / 删除 / 设定时;新建流程 |
| **设备控制** | 回桌面 / 返回 / 最近任务 / 唤醒;音量± / 静音;四方向滑动;**坐标测试板**(按下=点击,长按=长按,拖动=滑动);手填坐标 |
| **日志** | 板子最近 80 行日志,可清空 |

右上角「**地址**」按钮填板子 IP(在板子串口日志或网页地址栏里能看到,形如 `192.168.5.37`)。

## 编译

不需要 Android Studio / Gradle,用 SDK 自带的命令行工具直接编译:

```bash
cd android-app
./build.sh              # 输出 build/触摸注入板.apk
./build.sh --install    # 编译并 adb 安装到手机
```

编译流程:`aapt2 compile` → `aapt2 link` → `javac` → `d8` → `zip` → `zipalign` → `apksigner`。

**为什么不用 Gradle**:它要下载几百 MB 依赖,而这个 App 只有 3 个 Java 文件、
界面全部用代码构建(没有 layout XML),命令行工具链更快也更可控。

首次运行会自动生成 `debug.keystore`(自用签名,口令 `android`)。

## 自测(不用手点界面)

内置了一个调试广播接收器,可以用 adb 触发真实请求,结果打到 logcat:

```bash
# 读板子状态(最有用的一条)
adb shell am broadcast -a com.dsh.touchpad.DEBUG --es op sysinfo \
  -n com.dsh.touchpad/.DebugReceiver
adb logcat -d -s TouchPadDebug | tail -5
# 期望: SELFCHECK OK: mode=STA ip=... hid=true scripts=N heap=...

# 其他 op
adb shell am broadcast -a com.dsh.touchpad.DEBUG --es op scripts  -n com.dsh.touchpad/.DebugReceiver
adb shell am broadcast -a com.dsh.touchpad.DEBUG --es op logs     -n com.dsh.touchpad/.DebugReceiver
adb shell am broadcast -a com.dsh.touchpad.DEBUG --es op control:home -n com.dsh.touchpad/.DebugReceiver
adb shell am broadcast -a com.dsh.touchpad.DEBUG --es op gesture:tap  -n com.dsh.touchpad/.DebugReceiver
```

还可以临时指定别的地址(不用改设置):

```bash
adb shell am broadcast -a com.dsh.touchpad.DEBUG --es op status \
  --es host 192.168.5.99 -n com.dsh.touchpad/.DebugReceiver
```

## 排查:App 说"连不上板子"

1. **手机和板子在同一 WiFi?** 板子连的是 `ZTE-2009-2.4G`,手机也要连这个;
2. **地址填对了吗?** 看板子串口日志里的 `[net] 已连接,IP: ...`,或用电脑浏览器确认能打开;
3. **板子还活着吗?** 电脑上 `curl http://<板子IP>/api/status`。

## 排查:App 能用但手势没反应

这是**板子与手机之间的 HID 链路**问题,不是 App 问题。检查:

```bash
# 手机内核里还认得我们的设备吗(应该看到 3 个 ESP32S3 设备)
adb shell getevent -pl | grep -A1 ESP32S3

# 内核没有设备,但蓝牙里连接记录还在 → HOGP 掉线(常见)
adb shell dumpsys bluetooth_manager | grep "ESP32S3 Touch HID"
```

**如果内核里没有设备**:把手机**蓝牙关掉再打开**(或删掉配对重新配对),
让 HOGP 重新枚举。板子侧不用动。

> 注意:板子的 `/api/status` 里 `hid` 字段只表示"**传输层就绪**(已连接且已订阅)",
> **不代表手机一定枚举成功**。判断真正的可用性,要看手机内核里有没有 HID 设备。

## ⚠️ 重要:注入的触摸会打到「当前屏幕上是什么」

这是 HID 注入的**本质特性**,不是 bug,但极容易踩:

- 脚本里的坐标是**绝对位置**(0.5, 0.5 就是屏幕正中),**不管那里是什么**;
- 所以如果**本 App 在前台**,脚本的触摸就会点到 App 自己的按钮上;
- 实测案例:`曲线滑动` 的捏合中心在屏幕正中 `(540,1158)`,正好压住列表里的
  `循环5次`/`编辑` 按钮(约 y=1212/1341),结果"跑曲线滑动却打开了编辑弹窗"。

**App 侧的防护(已实现)**:

| 防护 | 作用 |
|---|---|
| **执行遮罩** | 脚本执行期间盖一层全屏遮罩,吃掉所有触摸并显示"停止执行" |
| **按钮锁定** | 执行中 + 结束后 1.2 秒内,所有按钮被 `canTap()` 拦下 |
| **列表防重绘** | 只有数据真的变化才重绘列表,避免按钮位置跳动导致误点 |
| **流程页警示** | 页面顶部常驻提示,提醒先切到目标 App 再运行 |

**但防护只覆盖"打到本 App"这一种情况。** 如果脚本坐标落在了别的 App 的按钮上,
那正是你想要的效果;如果落在了不该点的地方,只能靠**你自己确认坐标**。
运行前建议先用「手势」页的坐标板试一下落点。

## 已知限制

- **键盘注入不推荐使用**:Android 会弹"是否连入外接键盘",且中文输入法会把按键
  变成候选词 —— 所以脚本请用**触摸**实现(见上级 README);
- 脚本执行中的**进度在网页/App 上要等执行完才能看到**(执行是同步的,会占住主循环);
- App 目前不支持 HTTPS(板子是局域网明文 HTTP,已在 `network_security_config.xml` 放行)。
