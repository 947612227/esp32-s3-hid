# ESP32-S3 HID 触摸注入开发板

用一块 **ESP32-S3-N8R8** 做成 HID 复合设备(真·触摸屏 digitizer + 键盘 + Consumer + 鼠标),
可以注入点击 / 长按 / 滑动 / 曲线滑 / 双指手势。**支持 USB 和 BLE 两种连接方式,共用同一份
Report Map 与手势引擎。**

> ⚠️ **用途声明**:本工程是**通用输入注入研究/自动化测试**工具,用于学习 Android 输入子系统、
> OTG/BLE HID 协议、以及做设备兼容性测试。**不包含任何特定 App 的适配、坐标表或流程编排**,
> 也不用于绕过任何平台的风控或用户协议。请自行确认你的使用场景合法合规。

> ## 📖 想直接上手用?
>
> **看 [`docs/使用说明.md`](docs/使用说明.md)** —— 部署、配置、脚本语法、排障**全在一篇里**。
> 本文偏"原理和踩坑",那份偏"怎么用"。

## 现在这套系统长什么样

```
   ESP32-S3 板子 ──BLE HID──▶ 被控手机 (注入触摸)
        │  ▲
   心跳 │  │ 推页面
        ▼  │
   控制中心(阿里云 + MySQL) ◀── 手机 App (录流程/看日志/定时)
```

| 部分 | 状态 | 说明 |
|---|---|---|
| **固件** | ✅ | BLE / USB 两种传输,脚本引擎,热点配网,OTA |
| **手机 App** | ✅ | 可视化编排、坐标拾取、悬浮窗、定时、页面识别 |
| **控制中心** | ✅ | MySQL 台账、固件/APK 分发、网页看板、账号系统 |

## 这个工程里有两条独立的路

| | **有板方案**(`src/`) | **无板方案**(`adb-only/`) |
|---|---|---|
| 硬件 | ESP32-S3 开发板 | **不需要**,一根 USB 线 + 手机开 USB 调试 |
| 注入 | 板子做成 HID 设备(USB 或 BLE) | adb `input` 命令 |
| 感知屏幕 | ❌ 不能 | ✅ 截图 + UI 树 |
| 免 USB 调试 | ✅ | ❌ 需要 |
| 延迟 | 毫秒级 | 几十~几百 ms/次 |
| 文档 | 本文 | [`adb-only/README.md`](adb-only/README.md) |

**建议顺序**:先用 `adb-only/` 把"要点哪里、什么时候点"跑通(零成本),流程稳定后再决定
要不要换成板子。两条路共用同一套感知逻辑,注入层已抽象成 `Driver` 接口,切换成本很低。

---

## 有板方案:两种连接方式怎么选

| | **USB HID**(`-e usb`) | **BLE HID**(`-e ble`) |
|---|---|---|
| 接线 | 手机一根线(原生 USB) | 无需接线,蓝牙配对 |
| 首次授权 | 弹窗一次,勾"一律允许"后永久 | 系统蓝牙配对;部分 ROM 重启后可能重问 |
| 延迟 | 毫秒级,最跟手 | 7.5~15ms 连接间隔 + 唤醒延迟 |
| 稳定性 | 不受干扰 | 受 2.4G 干扰,连接间隔需调 |
| 额外时序坑 | 无 | **必须等 CCCD 订阅完成再发第一包** |
| 适用 | 桌面调试、机架群控 | 无线、手机只剩一个 Type-C 口时 |

**建议先做 USB 版**(问题面更小、自检更快),验证描述符正确后再上 BLE —— 两者 Report Map
完全一样,BLE 基本只是换传输层。


---

## 目录

- [1. 它解决的核心问题:为什么不能用"绝对坐标鼠标"](#1-它解决的核心问题为什么不能用绝对坐标鼠标)
- [2. 硬件与接线](#2-硬件与接线)
- [3. 编译与烧录](#3-编译与烧录)
- [4. 五分钟验证:确认被识别成真触摸屏](#4-五分钟验证确认被识别成真触摸屏)
- [4.5 BLE 版配对与验证](#45-ble-版配对与验证)
- [5. 命令协议](#5-命令协议)
- [6. 上位机 Demo](#6-上位机-demo)
- [7. "看"与"不看"——两种自动化架构的取舍](#7-看与不看两种自动化架构的取舍)
- [8. 已知坑与避坑清单](#8-已知坑与避坑清单)
- [9. 工程结构](#9-工程结构)
- [9.5 三星「是否连入外接键盘」弹窗](#95-已解决三星是否连入外接键盘弹窗)
- [10. 最新能力一览](#10-最新能力一览)
- [实施记录(全部文档)](#实施记录)

> ⚠️ **原来的 10~13 节(OTA / 自动发现 / 手机 App / 迭代记录)已经并入
> [`docs/使用说明.md`](docs/使用说明.md)**,本文不再重复。

---

## 1. 它解决的核心问题:为什么不能用"绝对坐标鼠标"

绝大多数网上能搜到的 "ESP32 + Android 触摸" 方案,用的是 **绝对坐标鼠标**
(`Usage Page 0x01 / Usage 0x02 Mouse` + 绝对 X/Y)。在 Android 上它的真实下场是:

| 描述符形态 | 内核产出 | Android 判定 | 实际表现 |
|---|---|---|---|
| 纯绝对鼠标(0x01/0x02 + 绝对 X/Y) | 只有 `ABS_X/ABS_Y` + `BTN_LEFT` | 既非 CURSOR(要 `REL_X/Y`)也非 TOUCH(要 `BTN_TOUCH`),`classes == 0` | **设备被整个丢弃**,`getevent` 都看不到 |
| 带 Tip Switch 的单点 digitizer(很多现成库就是这样) | `BTN_TOUCH` + `ABS_X/Y`,但**无 `INPUT_PROP_DIRECT`** | 判为 TOUCH → `computeDeviceType()` 落到 **POINTER** | **外接箭头鼠标**:能点,不能拖,不能多指 |
| 本工程(真 digitizer,含 `0x51`) | `INPUT_PROP_DIRECT` + `ABS_MT_*` + `BTN_TOUCH` | **TOUCH_SCREEN** | 真触摸:拖动 / 多指 / 缩放全可用 |

### 关键机制(自底向上三级)

**① 内核识别**

`hid_scan_input_usage()` 只要在 Input 项里看到 **Contact Identifier (`0x0D/0x51`)**,
就把设备归为 `HID_GROUP_MULTITOUCH`,绑定 `hid-multitouch` 驱动(其匹配表末尾有通配项,
自研硬件也能命中)。该驱动见到 `application == HID_DG_TOUCHSCREEN (0x0D/0x04)` 时:

```c
// drivers/hid/hid-multitouch.c 大致逻辑
td->mtclass.quirks |= MT_QUIRK_...
input_mt_init_slots(..., INPUT_MT_DIRECT);   // ← 给 evdev 打上 INPUT_PROP_DIRECT
```

产出 `ABS_MT_SLOT / ABS_MT_POSITION_X / ABS_MT_POSITION_Y / ABS_MT_TRACKING_ID` + `BTN_TOUCH`。

**② Android InputReader**

`EventHub.cpp` 里:
- 有 `ABS_MT_POSITION_X && ABS_MT_POSITION_Y` → `TOUCH | TOUCH_MT`
- 否则 `BTN_TOUCH && ABS_X && ABS_Y` → `TOUCH`(单点)
- `BTN_MOUSE + REL_X + REL_Y` → `CURSOR`

**③ 最后一道门**

```cpp
// TouchInputMapper::computeDeviceType()
if (mDeviceMode == DIRECT)  return TOUCH_SCREEN;   // ← 我们要的
if (mDeviceMode == POINTER) return POINTER;
return POINTER;                                     // ← 默认!这就是"箭头鼠标"的来源
```

### 结论(记住这三条就够)

1. **`0x0D/0x04` 顶层集合 + Input 项里的 `0x51`** 是开关,少了 `0x51` 一定退化成鼠标指针。
2. `0x54`(Contact Count)/ `0x55`(Maximum)/ `0x56`(Scan Time)**不是 Android 的判据**;
   `0x55` 做成 Feature Report 可以让内核的 maxcontacts 正确,本工程已经加了。
3. **Report ID 必须非 0 且与发送时严格一致;报文长度必须与声明完全吻合**,多一个字节整包被丢。

---

## 2. 硬件与接线

### 物料

| 物料 | 说明 |
|---|---|
| ESP32-S3 开发板 | 推荐 **ESP32-S3-DevKitC-1 N8R8**(8MB Flash + 8MB PSRAM),**必须带原生 USB 口** |
| 数据线 | **USB 版**:原生 USB ↔ 手机(USB-C to C 或按板子接口)+ UART ↔ PC 共 2 根;**BLE 版**:只需 UART ↔ PC 1 根 |
| 手机 | Android 手机,建议 Android 10+ |

> **USB 版为什么两条线**:ESP32-S3 的 **USB-OTG 与 USB-Serial-JTAG 共用 PHY**,同一时刻只能有一个工作。
> 本固件把**原生 USB 口**交给 HID(接手机),所以控制协议和日志走**独立的 UART0 外设**。
> 板子上通常有两个 USB 座:标 `USB` 的是原生 USB,标 `UART` 的是串口桥(CP2102/CH343)。

### 接线示意

**USB 版**(`-e usb`)

```
   ┌──────────────┐  原生 USB(HID)   ┌──────────────┐
   │ ESP32-S3     │──────────────────│  Android 手机 │
   │  DevKitC-1   │                  │  (USB Host)  │
   │              │                  └──────────────┘
   │              │  UART0(GPIO43/44) ┌──────────────┐
   │              │──────────────────│   PC / Mac   │
   └──────────────┘   115200 8N1     │  (控制+日志)  │
                                     └──────────────┘
```

**BLE 版**(`-e ble`)

```
   ┌──────────────┐   BLE HID(HOGP)  ┌──────────────┐
   │ ESP32-S3     │· · · · · · · · · │  Android 手机 │
   │  DevKitC-1   │   (蓝牙配对,无线) │              │
   │              │                  └──────────────┘
   │              │  UART0(GPIO43/44) ┌──────────────┐
   │              │──────────────────│   PC / Mac   │
   └──────────────┘   115200 8N1     │  (控制+日志)  │
                                     └──────────────┘
```

- 手机侧**不需要**装任何 App、**不需要** root、**不需要**开 USB 调试(两种版本都是)。
- USB 版插上后手机会弹一次"允许访问 USB 设备",勾选 **一律允许** 后不再询问。
- 供电:USB 版由手机 VBUS 提供;BLE 版由 PC 的 UART 口供电。ESP32-S3 满载约 200~300mA。
- **要边充边控**才有必要买"充电 + OTG 三合一"转接(仅 USB 版涉及)。
- 想在 BLE 版上**完全脱开 PC**,可以把控制通道改成板载按键/屏幕,或用第二块 MCU 下指令。

---

## 3. 编译与烧录

### 环境

```bash
pip install platformio          # 或者用 VS Code 的 PlatformIO 插件
```

### 编译与烧录

```bash
cd esp32s3-hid-touch

# --- USB 版 ---
pio run -e usb -t upload

# --- BLE 版 ---
pio run -e ble -t upload

# 看串口(控制协议 + 日志都在这里)
pio device list                    # 确认串口
pio device monitor -b 115200
```

烧录口用板子上的 **UART 座**(串口桥)。USB 版工作时,HID 走**原生 USB 座**,两个座互不冲突。

### 常用编译开关(`platformio.ini`)

| 宏 | 作用 |
|---|---|
| `EJ_TOUCH_DEMO=1` | 上电自动跑一段演示手势(默认关闭,避免误触) |
| `CONTROL_ON_UART1=1` | 控制协议挪到 UART1(GPIO17=TX / GPIO18=RX),UART0 只留日志 |
| `TOUCH_TIME_SCALE=2.0f` | 全局手势时长倍率,调试时放慢更好观察 |
| `HID_TOUCH_VID / _PID` | 自定义 VID/PID(默认沿用 Espressif 测试 VID `0x303A`) |
| `BOARD_HAS_PSRAM` | N8R8 用户可加,启用 8MB PSRAM(本 Demo 用不到,留给后续扩展) |

另外有一个预置的调试环境 `env:usb_debug`,已自动打开 `EJ_TOUCH_DEMO=1` 和 `TOUCH_TIME_SCALE=2.0f`,
用来慢速观察手势:

```bash
pio run -e usb_debug -t upload
```

> **编译时的 `warning: "ARDUINO_USB_MODE" redefined` 是正常的**,可以忽略。
> 原因:Arduino-ESP32 核心会自己定义这个宏,而 Adafruit TinyUSB 需要它是 `0`
> (表示原生 USB 走 TinyUSB 栈而不是 USB-Serial-JTAG),两者在同一编译单元里各定义一次。
> 只要 `platformio.ini` 里显式写了 `-DARDUINO_USB_MODE=0` 就是我们要的行为。
> 上电后串口应打印:`[boot] USB HID 就绪(原生 USB 口)`。


---

## 4. 五分钟验证:确认被识别成真触摸屏

### ✅ 真机实测记录(Samsung S22 Ultra / Android 16 / BLE 版)

**① 设备被识别成触摸屏**

```
Device 10: ESP32S3 Touch HID
    IsExternal: true
    Classes: TOUCH | TOUCH_MT | EXTERNAL
    Sources: KEYBOARD | DPAD | TOUCHSCREEN | JOYSTICK
Touch Input Mapper (mode - DIRECT):     <- DIRECT 而非 POINTER
    DeviceType: TOUCH_SCREEN            <- 判定为触摸屏
```

**② 内核侧属性正确**

```
/dev/input/event12  name: "ESP32S3 Touch HID"
  KEY (0001): BTN_TOUCH
  ABS_MT_SLOT         : min 0, max 9      <- 10 个槽位
  ABS_MT_POSITION_X   : min 0, max 32767  <- 与描述符声明一致
  ABS_MT_POSITION_Y   : min 0, max 32767
  ABS_MT_TRACKING_ID  : min 0, max 65535
  INPUT_PROP_DIRECT                        <- 真触摸屏标志
```

**③ 坐标精确(点击屏幕中心)**

```
ABS_MT_TRACKING_ID  00000000
ABS_MT_POSITION_X   00004000    <- 0x4000/0x7FFF = 50.0%
ABS_MT_POSITION_Y   00004000
BTN_TOUCH           DOWN
```

**④ 滑动是平滑轨迹(内核连续采样)**

```
ABS_MT_POSITION_Y   00002ccc -> 00002e0a -> 00002f47 -> ...
                    (11468)     (11786)     (12103)      递增,非跳变
```

**⑤ 端到端效果验证(系统级手势)**

从屏幕顶部下拉(`S 0.5 0.01 0.5 0.75 500`)后,前台从桌面变成
`mCurrentFocus=NotificationShade` —— **通知栏被拉出来了**。
这是系统手势,坐标必须精确到能跨越状态栏,排除了巧合的可能。

### 系统导航键怎么点(键盘不可用时的替代方案)

因为键盘注入不生效(见 8.5),**返回 / 回桌面 / 最近任务** 这三个动作要靠**点底部导航栏**实现。

先确认你的导航模式,再取坐标:

```bash
adb shell screencap -p /sdcard/nav.png && adb pull /sdcard/nav.png
```

**三星 S22 Ultra(1080×2316,三键导航)实测坐标**:

| 按键 | 像素 | 归一化 |
|---|---|---|
| 最近任务 | (234, 2230) | `0.22, 0.963` |
| **回桌面** | (540, 2230) | `0.50, 0.963` |
| **返回** | (845, 2230) | `0.78, 0.963` |

**三个动作各管各的,不要混在一个脚本里**:

| 想做的事 | 用哪个脚本 | 几步 |
|---|---|---|
| 回桌面 | `回桌面` | **1 步**(点方块就够,不需要先按返回) |
| 返回一级 / 关弹窗 | `返回` | 1 步 |
| 打开最近任务 | `最近任务` | 1 步 |
| 逐级关掉几个弹窗再回桌面 | `逐级返回后回桌面` | 组合写法 |

> 常见误解:以为"回桌面"要先按几次返回 —— 不需要。**方块键一步到位。**
> 返回键只在"需要退出一层子页面/关掉弹窗"时才用。

> ⚠️ 注意 `settings get secure navigation_mode` 这个读数**不可靠** —— 本机读出 `0`
> (按 AOSP 含义是手势导航),但截图显示实际是三键导航。**以截图为准。**

### ⚠️ 三个必须知道的坑(都实测踩过)

这三个坑的共同症状是:**设备识别完全正常(属性、分类全对),但 `getevent` 收不到任何事件或收不到坐标**。

| # | 坑 | 症状 | 正确做法 |
|---|---|---|---|
| 1 | 触点 X/Y 用"扁平数组"布局(先 10 个 X 再 10 个 Y) | 有 `BTN_TOUCH`,但 `ABS_MT_POSITION_*` 一个都没有 | 必须用**每指一个 `Usage(Finger)` + `Collection(Logical)`,触点数据按指交错**,末尾用 `Report Count(10)` 重复整组 |
| 2 | Tip Switch / In Range 在结构体里写成两个 `uint8_t` | 比描述符多 1 字节 → **整包被丢弃**,连 `BTN_TOUCH` 都没有 | 两者在描述符里共用 1 字节(bit0/bit1),结构体必须合并成一个 `uint8_t` |
| 3 | BLE 侧没把 Report ID 作为报文首字节发出 | 设备枚举正常,但主机匹配不到 Report → 静默丢弃 | BLE 手工补 `[ReportID][载荷]`;**USB 不需要**(Adafruit `sendReport()` 内部会加) |
| 4 | **描述符里混入多余字节,导致 X/Y 字段错位** | 内核只声明 `ABS_PRESSURE` 而**没有 `ABS_MT_POSITION_X/Y`**;我们发的 X 被当成"压力" | 见下方「描述符必须做字节级校验」 |

### 描述符必须做字节级校验(踩坑 4 的教训)

一次用脚本批量改描述符时,不小心多插了一个 `0x30` 字节,结果:

```
内核声明的轴:ABS_PRESSURE / ABS_MT_SLOT / ABS_MT_TRACKING_ID   ← 没有 POSITION!
发 (0.25, 0.75) → 内核报 ABS_MT_PRESSURE = 0x2000 (= 32767×0.25)  ← X 被当成压力
```

**症状和"触摸完全不生效"一模一样,但根因完全不同**(一个是数据格式,一个是描述符结构)。
判断方法:`adb shell getevent -pl /dev/input/event12 | grep ABS_MT_POSITION`
—— **没有 POSITION 轴就是描述符坏了**。

**所以改完描述符一定要逐字节核对**,别只看"能不能编译"。最可靠的办法是让编译器
把真实字节 dump 出来比对(源码里用宏名如 `RID_TOUCHSCREEN`,肉眼看容易漏):

```bash
# 把数组抽出来交给 gcc 编译后打印
gcc -o /tmp/dump /tmp/dump_desc.c && /tmp/dump
# 期望的触点集合前缀:
# 05 0D 09 04 A1 01  85 01  09 22 A1 02  09 42 ... 09 32 ... 09 51 ...
# 05 01  09 30  09 31  16 00 00  26 FF 7F  75 10  95 02  81 02  95 0A  C0
```


> 排查手法:先用 `adb shell getevent -il <节点>` 看**内核**有没有收到事件。
> 内核收不到 → 描述符/报文长度问题(上表 1~3);
> 内核收到了但 App 没反应 → 才是应用层的事。**不要混着查。**

## 5. 命令协议

串口 **UART0,115200,8N1**。每行一条命令,`\r\n` 或 `\n` 结尾。
**坐标全部归一化到 `0..1`**(x 向右,y 向下),这样和手机分辨率解耦。

| 命令 | 格式 | 说明 |
|---|---|---|
| `T` | `T x y [ms]` | 点击,默认 40ms |
| `D` | `D x y [gap]` | 双击,默认间隔 90ms |
| `H` | `H x y [ms]` | 长按,默认 900ms |
| `S` | `S x1 y1 x2 y2 [ms]` | 直线滑动,默认 220ms(带轻微垂直抖动) |
| `C` | `C x1 y1 cx cy x2 y2 [ms]` | 二次贝塞尔曲线滑动 |
| `P` | `P x1 y1 x2 y2 x3 y3 x4 y4 [ms]` | 双指滑动(两组起点 → 两组终点) |
| `Z` | `Z cx cy gap1 gap2 [ms]` | 双指捏合/张开,围绕中心点 |
| `M` | `M dx dy` | 鼠标相对移动(-127..127) |
| `B` | `B [n]` | 鼠标左键点击 n 次 |
| `K` | `K usage` | 键盘按键(USB HID usage,十进制) |
| `V` | `V usage` | Consumer 键(如 233=音量+,234=音量-) |
| `G` | `G` | 自检:点四角 + 中心 |
| `U` | `U` | 立即抬起全部触点 |
| `R` | `R` | 复位状态 |
| `?` | `?` | 打印帮助 |

### 常用键盘 usage

| 键 | 十进制 | 十六进制 |
|---|---|---|
| Enter | 40 | 0x28 |
| Esc(= Android BACK) | 41 | 0x29 |
| Home | 74 | 0x4A |
| 音量 + | 233 | 0xE9(Consumer,用 `V`) |
| 音量 − | 234 | 0xEA(Consumer,用 `V`) |

### 示例

```
S 0.5 0.75 0.5 0.30 260          # 从下方 3/4 处上滑到 3/10 处,260ms
T 0.5 0.5                        # 点屏幕中心
P 0.4 0.7 0.4 0.3 0.6 0.7 0.6 0.3 260   # 双指上滑(类似双指滚动)
Z 0.5 0.5 0.55 0.20 300          # 以中心为轴捏合
```

---

## 5.5 脚本引擎(动作序列存在 Flash 上)

固件内置了一个**通用手势脚本引擎**:把动作序列存到板子的 Flash(LittleFS)上,
运行时可加载、列表、执行。**它不含任何具体 App 的坐标或流程 —— 脚本内容完全由你提供。**

### 为什么要有它

- 板子可以**脱机独立运行**,不依赖 PC 一直在场;
- 动作序列**断电不丢**,改流程不用重新编译烧录;
- 同一块板子可以存多套流程,按名字/序号切换。

### 脚本语法

脚本存在 `/scripts/<name>.txt`,**一行一个动作**,`#` 开头是注释:

| 指令 | 写法 | 说明 |
|---|---|---|
| 点击 | `T x y [ms]` | 默认 40ms |
| 双击 | `D x y [gap]` | 默认间隔 90ms |
| 长按 | `H x y [ms]` | 默认 900ms |
| 直线滑 | `S x1 y1 x2 y2 [ms]` | 默认 220ms |
| 曲线滑 | `C x1 y1 cx cy x2 y2 [ms]` | 二次贝塞尔,`cx cy` 是控制点 |
| 双指滑 | `P x1 y1 x2 y2 [gap] [ms]` | 两指水平相距 gap,同步滑动 |
| 捏合 | `Z cx cy gap1 gap2 [ms]` | gap1 > gap2 是捏合,反之张开 |
| 按键 | `K usage` | USB HID usage,如 `K 41`=BACK、`K 74`=Home |
| 多媒体 | `V usage` | 如 `V 233`=音量+ |
| 等待 | `wait ms` | 暂停 |
| 重复 | `repeat n` … `endloop` | 把两者之间的步骤重复 n 次(1~65535),**必须成对**,可嵌套;缺一个保存时就报错 |
| 跳出 | `break [备注]` | 立即跳出最内层 `repeat` 循环(写在循环外保存时报错) |
| 通知 | `notify 消息` | 往控制中心报一个事件(整行都是消息) |
| 跳转 | `goto label` | 跳到 `#label` 所在位置 |

> 上限:每个流程最多 **128 步**(`ifpage`/`else`/`endif`/`endloop` 每个都算一步,标号和注释不算),
> 板子最多存 **32 个流程**(没有 PSRAM 的板子只有 4 个);每个流程标号、`goto` 各最多 16 个。
> 超出都会在保存时报错,不会静默截断或丢弃。

> 所有指令都支持**末尾加备注**,例如 `wait 1500 等加载`、`H 0.5 0.5 900 长按菜单`。

**备注(可选)**:参数**后面**接一段文字,日志和界面会用人类语言显示它:

```
T 0.923 0.911 60 点我的
wait 3000 等页面加载
S 0.5 0.75 0.5 0.25 260 上滑列表
```

执行日志会输出 `[1/3] 点击:点我的` 而不是一串坐标。
**不写备注也完全可以**,那样就按坐标显示(`点击 (50%, 50%)`)。

> 规则:从第一个**非数值**的词开始,后面全部算备注。
> 所以 `T 0.5 0.5 点我的`(省略 ms)也合法。

**坐标三种写法都支持**,可以混用:

```
T 0.5 0.5        # 归一化(推荐,换分辨率不用改)
T 50% 50%        # 百分比
T 16384 16384    # 逻辑坐标 0..32767
```

**标号与跳转示例**:

```
# 这一行是标号,不产生动作
T 0.5 0.8
wait 500
goto start        # 跳回标号,形成循环
```

> ⚠️ 有死循环保护:**连续 2000 步都没有点击 / 等待 / 通知**(只在 goto、判断之间空转)就报错退出。
> 正常的长循环(比如 `repeat 300` 里点点点)不受影响,随时可以「停止」。

### 串口命令

| 命令 | 说明 |
|---|---|
| `LIST` | 列出所有脚本 |
| `LIST <序号>` | 列出某个脚本的**全部步骤**(用于核对坐标) |
| `RUN <名字\|序号>` | 执行脚本 |
| `DEL <名字>` | 删除脚本 |
| `RELOAD` | 重新扫描 `/scripts` 目录 |
| `SAVE <名字>` | **进入录制**:之后每行都按脚本语法存起来 |
| `END` | 结束录制并保存 |
| `CANCEL` | 放弃录制 |
| `FILES` | 查看文件系统占用 |

**用串口当场录一段**:

```
SAVE myflow
T 0.5 0.5
wait 500
S 0.5 0.8 0.5 0.2 300
END
LIST 0
RUN myflow
```

### 从 PC 下发脚本文件(推荐)

```
python3 tools/upload_script.py --port /dev/cu.usbmodemXXXX scripts/scroll_down.txt
python3 tools/upload_script.py --port ... --list
python3 tools/upload_script.py --port ... --show scroll_down
python3 tools/upload_script.py --port ... --run scroll_down
```

> 板子的串口输入缓冲只有 128 字节,所以这个工具**逐行发送并限速**,
> 不能一次性灌整个文件 —— 直接 `cat` 或粘贴大段会丢行。

### 首次开机自带示例

固件在**流程目录为空**时会自动写入 **7 个中文示例流程**(回桌面 / 返回 / 最近任务 /
逐级返回后回桌面 / 向下滚动 / 重复演示 / 曲线滑动),方便你参考语法。
**它们不会自动执行**,只有你在网页上点"运行"或串口 `RUN` 才会动作。

> 恢复出厂后重启,这 7 个示例会重新写回。

### 示例脚本文件

`scripts/` 目录下放了可直接下发的示例:

**全部用中文命名,网页上直接显示中文**(LittleFS 支持 UTF-8 文件名,浏览器会自动做 URL 编码):

| 文件 | 内容 |
|---|---|
| `回桌面.txt` | 回桌面(点方块键,一步) |
| `返回.txt` | 返回上一级 / 关弹窗 |
| `最近任务.txt` | 打开多任务界面 |
| `逐级返回后回桌面.txt` | 先退几级再回桌面 |
| `向下滚动.txt` | 向下滚动三屏 |
| `重复演示.txt` | `repeat` 用法演示 |
| `曲线滑动.txt` | 曲线滑动 + 双指捏合 |

> **文件名、注释、流程名都可以用中文。** 只有串口命令里用中文名时要注意终端编码,
> 用网页操作最省心。

### 想扩展的方向

- **板载屏幕菜单**:挂一块 ST7789/IPS 屏 + LVGL,N8R8 的 8MB PSRAM 完全够,
  可以在板上直接选脚本运行,连串口都不需要;
- **物理按键切换**:`main.cpp` 里已经预留了 BOOT 键的中断处理,可以改成"按一下切下一个脚本";
- **定时触发**:脚本引擎是阻塞式执行,加个 `millis()` 判断就能做成"每隔 N 秒跑一次";
- **条件分支**:解析器里已经预留了 `#if` / `#end` 关键字的位置(目前不参与解析)。

## 5.8 WiFi + 网页管理(在电脑上管理板子)

板子启动时会连 WiFi,然后开一个网页控制台。**电脑浏览器直接操作,不用接线、不用串口。**

### 配网

1. 复制 `secrets.example.h` 为 `secrets.h`,填上 WiFi 名和密码(`secrets.h` 已在 `.gitignore` 里,不会进版本库);
2. 烧录后看串口:

```
[net] 连接 WiFi: 你的WiFi名
[net] 已连接,IP: 192.168.5.37
[net] Web 管理已启动: http://192.168.5.37/
```

3. 电脑浏览器打开 `http://192.168.5.37/`。

**连不上会自动回退到热点模式**:板子自己开一个 `ESP32-S3_XXXX` 热点(XXXX = MAC 最后 4 位,和蓝牙名一样;
1.5.0 以前的固件叫 `ESP32-Touch-XXXX`,密码默认 `12345678`),
电脑连上它再访问 `http://192.168.4.1/` 即可。**保证永远能管理到板子。**

### 网页能做什么

| 功能 | 说明 |
|---|---|
| 脚本列表 | 显示每个脚本名 / 步骤数 / 每步明细 |
| 运行 | 点一下就在手机上执行(异步,不阻塞页面) |
| 编辑 | 把脚本还原成文本填进编辑框 |
| 新建 / 覆盖 | 填名字 + 内容,点保存即写入板子 Flash |
| 删除 | 删掉不需要的脚本 |
| 状态栏 | 实时显示 WiFi 模式、IP、**HID 是否就绪**、脚本数;标题旁是板子名字和备注(如 `ESP32-S3_098C(大麦1号)`) |
| 板子状态 | 网络模式/IP、空闲内存、运行时长、脚本数、Flash 占用、执行次数与上次结果 |
| 设备控制 | 一键**回桌面/返回/最近任务/唤醒**;音量键;四方向滑动 |
| 坐标测试 | **点一下网页上的方块,手机屏幕对应位置就被点一下**(用来快速试坐标);也支持手填 `0.5,0.5` / `50%,50%` 后点击或长按 |
| 定时循环 | 选一个脚本 + 间隔秒数,板子就按间隔反复执行,可随时停(实测每 5 秒一次计数正确递增) |
| 系统操作 | 停止脚本、抬起全部触点、清空日志、**改备注**、**重启板子**、**恢复出厂(清空脚本)** |
| 运行日志 | 板子最近 80 行日志实时滚到网页上,带自动滚动开关和清空按钮 |

### REST API(想自己写脚本/接入自动化时用)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 网络/内存/运行时长/脚本数/HID 状态/执行进度/定时状态 |
| GET | `/api/scripts` | 全部脚本 + 每步明细 |
| GET | `/api/script?index=N` | 单个脚本的文本形式(编辑用) |
| POST | `/api/run?index=N&repeat=R&interval=MS` | 执行脚本,可指定重复次数与间隔 |
| POST | `/api/stop` | 请求停止(当前步骤结束后生效) |
| POST | `/api/save?name=X` | 保存脚本(请求体为文本,**需 `Content-Type: text/plain`**) |
| POST | `/api/delete?name=X` | 删除脚本 |
| POST | `/api/control?do=X` | 设备控制:X = `home`/`back`/`recents`/`wake`/`power`/`volup`/`voldown`/`mute`/`releaseall` |
| POST | `/api/gesture?op=X&...` | 手势:`tap`/`double`/`long`/`swipe`/`curve`/`pinch`/`releaseall` |
| POST | `/api/schedule?index=N&sec=S` | 定时循环:每 S 秒执行一次脚本 |
| POST | `/api/schedule/stop` | 停止定时 |
| POST | `/api/system?op=reboot` | 重启板子 |
| POST | `/api/system?op=factory` | 恢复出厂(清空脚本,重启后写回示例) |
| POST | `/api/remark?remark=X` | 改备注:板子先问服务器查重(重名 409);服务器连不上就先存板子上、下次心跳同步。带 `&from=server` = 服务器已查过重,直接写(App 用) |
| GET | `/api/logs` | 最近 80 行日志 |
| POST | `/api/logs/clear` | 清空日志缓冲 |

**手势接口参数**:坐标用 `a/b/c/d`(归一化或百分比),或用 `xy=0.5,0.5` 一次给一个点。

```bash
B=http://192.168.5.37
# 点一下屏幕中心
curl -s -X POST "$B/api/gesture?op=tap&xy=0.5,0.5"
# 从底部上滑
curl -s -X POST "$B/api/gesture?op=swipe&a=0.5&b=0.75&c=0.5&d=0.25&ms=300"
# 回桌面 / 返回 / 最近任务
curl -s -X POST "$B/api/control?do=home"
curl -s -X POST "$B/api/control?do=back"
# 音量
curl -s -X POST "$B/api/control?do=volup"
# 跑 5 次,每次间隔 1 秒
curl -s -X POST "$B/api/run?index=0&repeat=5&interval=1000"
# 每 60 秒循环跑脚本 0
curl -s -X POST "$B/api/schedule?index=0&sec=60"
curl -s -X POST "$B/api/schedule/stop"
```

命令行示例:

```bash
B=http://192.168.5.37
curl -s $B/api/status
curl -s $B/api/scripts
curl -s -X POST "$B/api/run?index=3"
curl -s -X POST "$B/api/save?name=myflow" -H "Content-Type: text/plain" \
     --data-binary $'T 0.5 0.5\nwait 300\nS 0.5 0.7 0.5 0.3 300\n'
```

### 日志是怎么到网页的

板子维护一个 **80 行环形缓冲**(`log_buffer.h`),关键日志同时写到串口和这个缓冲;
网页每 2 秒拉一次 `/api/logs` 增量渲染。所以**插着手机、看不到串口时,也能在电脑上盯运行情况**。

代码里用 `LOGF(...)` / `LOGLN(...)` 写日志(用法同 `Serial.printf`),就会同时进缓冲和串口。

### ⚠️ WiFi 与 BLE 共存的代价

ESP32-S3 的 **WiFi 和 BLE 共用同一个射频**,同时开启会有共存开销,**BLE HID 的延迟会变差**。

- 所以固件里**必须允许 WiFi modem sleep**(`WiFi.setSleep(true)`)。实测关掉它会让底层直接
  报 `Should enable WiFi modem sleep when both WiFi and Bluetooth are enabled` 并**无限重启**;
- 如果你发现触摸变得不跟手,可以通过串口发 `WIFI OFF` 关掉网络,把射频全部让给 BLE;
- 反过来,如果你**只用网页管理、不需要 BLE**,也可以考虑把触摸改成 USB 传输(需要烧 eFuse,见前文)。

## 5.9 手机 App(APK)

除了板子自带的网页控制台,还有一个 **Android App** —— 这是日常主力。

```bash
cd android-app
./build.sh                # 编译,产出 build/触摸注入板.apk(约 113KB)
./build.sh --install      # 顺便装到手机
```

- **不需要 Android Studio / Gradle**,直接用 SDK 命令行工具编译;
- **板子 IP 不用手填** —— App 用 UDP 广播自动发现,换网络也能自己找回;
- 悬浮窗 5 个按钮:**拖动 / 让路 / 坐标拾取 / 复制页面句柄 / 收起**;
- 可视化编排流程 + **坐标拾取器**(在真实界面点哪儿取哪儿);
- **页面识别**(无障碍服务,只看不点):悬浮窗直接显示"现在在哪个页面";
- 定时(指定时刻 / 倒计时)+ 频率(带连续时长控制);
- 启动弹「使用前须知」;更新弹窗**每天一次**。

> **完整用法见 [`docs/使用说明.md`](docs/使用说明.md)。**

## 6. 上位机 Demo

```bash
pip install pyserial
python3 tools/host_demo.py
```

一个 tkinter 小界面:

- **触摸板区域**:鼠标**单击** = 点击,**按住不动** = 长按,**按住拖动** = 滑动(时长按你实际拖动的耗时算);
- **预设按钮**:上下左右滑、曲线滑、双指手势、Home/返回/音量、鼠标点击;
- **原始命令框**:直接敲协议命令,回车发送;
- **日志**:同时显示你发出的命令和板子回传的日志。

自动选中串口时会优先匹配 `usbmodem` / `wchusbserial` / `CP210` 等关键字。

---

## 7. "看"与"不看"——两种自动化架构的取舍

这是**做自动化时权重最高的一个决策**,比"用 HID 还是 adb"更重要。把能力拆成两块:

| 能力 | 技术手段 | 成熟度 |
|---|---|---|
| **手**(注入触摸) | HID digitizer(本工程) | ⭐⭐ 描述符对了就通 |
| **眼**(判断该点哪) | 截图/UI 树/摄像头 + 识别 | ⭐⭐⭐⭐ 通道多,各有硬伤 |

### 路线 A:不看(固定坐标 + 等待 + 重试)

```
ESP32-S3 ──USB HID──> 手机
   固件内固定坐标 + 状态机
```

- **优点**:零 PC 依赖、延迟最低(毫秒级)、最稳、最简单;
- **代价**:布局一变就失效;
- **适用**:目标控件位置稳定、只关心"时机"的场景。

**先验证再决定**:同一个按钮重复进入几次,坐标是否一致?一致 → 就走 A,不要引入视觉。

### 路线 B:看(必须有感知通道)

```
[PC] 截图/UI树 + 识别/决策 ──串口/WiFi──> [ESP32-S3] ──USB HID──> 手机
```

三条感知通道对比:

| | **PC 走 adb** | **外接摄像头 + OpenCV** | **手机端截屏 App** |
|---|---|---|---|
| 原理 | `adb exec-out screencap` / `uiautomator dump` | 拍屏幕做模板匹配/OCR | App 内截屏或读 UI 树 |
| 延迟 | 150~400ms | 100~300ms | 50~200ms |
| 精度 | 像素级 | 需标定,受对焦/反光影响 | 像素级 |
| 软件痕迹 | **要开 USB 调试** | **无** | 需装 App |
| 需 PC 常驻 | ✅ | 视部署位置 | ❌ |

**路线 B 的三个硬约束**:

1. **ESP32-S3 只有一个 USB-OTG,不能同时当 Host 和 Device** ——
   "自己看 + 自己点"在 S3 上不存在。要并行得换 **ESP32-P4**(host/device 独立控制器)或双芯片。
2. **手机只有一个 USB-C 口** —— 如果 adb 采集和 HID 都要占它,需要**带供电的 Hub**,且要实测能否同时枚举。
   摄像头方案不占 USB 口,这是它被低估的优势。
3. **延迟预算** —— 感知 + 决策 + 注入的总延迟要小于你的操作窗口,否则"看"得再准也没用。

### 怎么选

```
需要"看"吗?
├─ 不需要 ──────────────> 路线 A:单板 HID,最轻
└─ 需要
   ├─ 能接受 PC 常驻 ────> 路线 B-adb:最快能落地
   ├─ 要求手机侧零软件痕迹 ─> 路线 B-摄像头:最干净,工程量最大
   └─ 想装 App ──────────> 路线 B-手机端:最轻,但违背"免装 App"
```

---

## 8. 已知坑与避坑清单

### 8.1 描述符相关(最高频)

| 症状 | 原因 | 处理 |
|---|---|---|
| 设备能被识别但只是"箭头鼠标" | 缺 `0x51` Contact Identifier | 用本工程的 Report Map,别用网上现成的鼠标库 |
| `getevent` 里根本看不到设备 | 纯绝对鼠标描述符 → `classes == 0` 被丢弃;或插了 UART 口 | 换描述符 / 换到原生 USB 口 |
| 报文时有时无 | Report ID 或报文长度与声明不一致 | `sizeof(TouchReport)` 用 `static_assert` 卡住(本工程已加) |
| 双指无效 | 缺 Contact Count / 未按槽位分配 Contact ID | 用 `P` / `Z` 命令,`_cid[0]=1, _cid[1]=2` |

### 8.2 坐标映射

逻辑范围是 `0..32767`,由 Android 自行映射到屏幕。如果发现坐标整体偏移/缩放:

1. **先确认手机是否在横屏** —— 横屏时映射关系会变,`0..32767` 对应的方向也变;
2. 用 `G`(自检四角)观察实际落点,推断是否需要改 `TOUCH_LOGICAL_MAX`;
3. 有些 ROM 会参考物理尺寸做缩放,必要时在 Report Map 里补 `Physical Maximum`;
4. 三星类机型可查 `dumpsys input` 里触摸屏的 `X/Y: min/max`,拿它做参照。

### 8.3 手势相关

| 症状 | 原因 | 处理 |
|---|---|---|
| 滑动被识别成点击 | 只发了 DOWN→UP,没有中间 MOVE | 本工程插值 8~15 帧,别退化成两帧 |
| 滑动卡顿/断触 | 端点忙时丢包 | `flush()` 里有 `tud_hid_ready()` 等待,别去掉 |
| 双指变单指 | 两指在同一时刻按下被合并 | 本工程两指之间 `sync()` 隔开,间隔 >4ms |
| 长按不触发 | 时长不够 | 一般要 ≥500ms,`H` 命令默认 900ms |

### 8.4 BLE 相关

| 症状 | 原因 | 处理 |
|---|---|---|
| 第一次滑动手势没反应 | CCCD 还没订阅就发了报文,静默丢失 | 固件用 `onSubscribe` 跟踪订阅,`ready()` 未就绪会丢弃手势;看串口是否打印"已订阅" |
| 配对成功但设备不出现 | Report Reference Descriptor 缺失或 Report ID 对不上 | 每个 Input Report 必须有 `0x2908`,值 = [Report ID, 0x01] |
| 能点击但滑不动 | 同上,或连接间隔太大 | 固件请求 7.5~15ms;检查手机蓝牙设置里该设备的连接状态 |
| 重新烧录固件后连不上 | 旧的 bonding 信息不匹配 | 手机端**先删除配对**再重新配对 |
| 只有部分手势生效 | 报文字节数与 Report Map 声明不符 | 触摸报文载荷必须是 60 字节(10 个触点 × 6 字节),BLE 发送时前面再加 1 字节 Report ID |

### 8.5 架构相关

1. **ESP32-S3 单 OTG**:Host(ADB)与 Device(HID)不可并行;USB-OTG 与 USB-Serial-JTAG 也互斥。
2. **外部设备身份会被标记**:`InputDevice.isExternal()` 对 `BUS_USB`/`BUS_BLUETOOTH` 恒为 `true`,
   任何 App 都能查到。这是协议层事实,无法通过改描述符消除。
3. **`FLAG_SECURE` 只防截屏,不拦输入**;而"不可信触摸"拦截是按**窗口遮挡**判定
   (`TouchOcclusionMode::BLOCK_UNTRUSTED`),不按设备是否外部。
4. **`getevent` 是排查一切问题的第一入口** —— 内核收不到就说明描述符有问题,内核收到但 App 没反应
   就是应用层的事,别混着查。

---

## 8.5 ⚠️ 已知问题:键盘(按键)注入在 Android 上不生效

**当前状态:触摸注入完全正常,但键盘/Consumer 按键在 Android 上不被处理。**

实测证据(2025 版固件 + Samsung S22 Ultra / Android 16):

| 层级 | 结果 |
|---|---|
| 内核键盘节点 `/dev/input/event13` | ✅ **正常收到** `EV_MSC MSC_SCAN 00070029` + `EV_KEY KEY_ESC DOWN/UP` |
| Android 输入分发器 | ❌ **没有对应的 KeyEvent** —— 按键被系统层丢弃 |
| 实际效果 | 设置页里发 BACK/Home,前台不变;文本框中打字无输入 |

**已排除的原因**:

1. ~~描述符/报文错误~~ —— 内核能正确解析出 `KEY_ESC`/`KEY_ENTER`,说明报告格式没问题;
2. ~~设备被误判为游戏手柄~~ —— 这条**确实存在过**(描述符里用了 Generic Desktop 页的 X/Y,
   导致 `Sources` 里多出 `JOYSTICK | DPAD`),**已修**:触点坐标改用 Digitizer 页的 0x30/0x31。
   修完设备分类变干净(`TOUCH | TOUCH_MT | EXTERNAL`),但键盘仍未生效;
3. ~~三星"是否连入外接键盘"确认框未确认~~ —— 已用 HID 触摸点掉"是",仍然无效。

**尚未排除 / 下一步可查的方向**:

- `dumpsys input` 里该设备的 `KeyLayoutFile` / `KeyCharacterMapFile` 为**空**,
  对比一台真实外接键盘看这两项是什么,可能需要通过 `idc`/`kl` 文件补齐(需 root 或系统权限);
- 三星对**外部 HID 键盘**可能有额外输入策略(可查 `dumpsys input` 的 policy 段);
- 把键盘和触摸屏拆成**两个独立的 HID 设备**(BLE 上两个 HID instance),
  避免"复合设备里键盘 collection 被触摸 section 掩盖"的可能。

**影响范围**:只影响 `K`(按键)与 `V`(多媒体键)命令、以及脚本里的 `key`/`consumer` 步骤。
**触摸相关的全部功能(点击/长按/滑动/曲线/双指/捏合)不受影响**,脚本引擎也照常工作。

## 9. 工程结构

```
esp32s3-hid-touch/
├── platformio.ini                # 两个构建环境:env:usb / env:ble
├── README.md                     # 本文档
├── src/
│   ├── common/                   # ★ 两种传输共用
│   │   ├── hid_transport.h       #  传输后端抽象接口(HidTransport)
│   │   ├── touch_limits.h        #  触点数 / 逻辑坐标范围(纯常量,解析器也用)
│   │   ├── hid_report_map.h      #  ★ Report Map + 报文结构体(核心)
│   │   ├── hid_touch.h           #  手势引擎接口
│   │   ├── hid_touch.cpp         #  手势引擎实现(插值/贝塞尔/双指)
│   │   ├── script_engine.h       #  ★ 脚本引擎接口
│   │   ├── script_parser.cpp     #  流程文本解析(纯 C++,tests/native 在电脑上测它)
│   │   ├── script_engine.cpp     #  LittleFS 存储 + 执行
│   │   ├── net_manager.h         #  WiFi + Web 管理接口
│   │   ├── net_manager.cpp       #  WiFi 连接 + HTTP 服务 + 内嵌网页
│   │   └── main.cpp              #  串口命令协议 + 脚本命令 + 自检
│   ├── usb/
│   │   └── hid_transport_usb.cpp #  USB 后端(Adafruit TinyUSB)
│   └── ble/
│       └── hid_transport_ble.cpp #  BLE 后端(NimBLE,HID over GATT)
├── partitions_8mb.csv            # 分区表(为 LittleFS 留 1MB 存脚本)
├── secrets.example.h             # WiFi 凭据模板(复制为 secrets.h)
├── scripts/                      # 示例脚本(可用 tools/upload_script.py 下发)
└── tools/
    ├── host_demo.py              # 上位机 tkinter Demo + 触摸板
    └── upload_script.py          # 把脚本文件下载到板子 Flash
└── adb-only/                     # ★ 无板方案(独立,不需要 ESP32)
    ├── click.py                  #   CLI 入口
    ├── noboard/                  #   adb 驱动 / 目标解析 / 注入抽象 / 动作层
    ├── scripts/demo.txt          #   示例脚本
    └── README.md                 #   无板方案文档
```

**架构要点**:手势引擎只依赖 `HidTransport` 抽象接口,不关心报文是走 USB 还是 BLE。
`build_src_filter` 按环境只编译对应的后端,`hidTransport()` 工厂由该后端提供实现。
所以**加新传输方式(比如 WiFi/UDP)只需新增一个后端文件**,手势代码一行都不用改。

### 想扩展的地方

- **加 Report ID 7/8 做更细的设备划分**(BLE 上也可以把键盘和触摸屏拆成两个 HID instance);
- **加 WiFi 控制通道**替代串口(注意:S3 只有一个 USB-OTG,别和 HID 抢);
- **加板载屏幕 + 脚本存储**:N8R8 有 8MB PSRAM + 8MB Flash,跑 LVGL 和存手势脚本绰绰有余;
- **让板子自己"看图"**:S3 做不了(单 OTG 不能同时 host+device),要换 ESP32-P4 或双芯片。


---

## 附:参考来源

- Linux `drivers/hid/hid-core.c`(`hid_scan_input_usage` 与 `HID_GROUP_MULTITOUCH`)
- Linux `drivers/hid/hid-multitouch.c`(`INPUT_MT_DIRECT` / `input_mt_init_slots`)
- Linux `drivers/hid/hid-input.c`(Tip Switch → `BTN_TOUCH`,Digitizer → `INPUT_PROP_*`)
- AOSP `frameworks/native/services/inputflinger/reader/EventHub.cpp`(输入设备分类)
- AOSP `.../reader/mapper/TouchInputMapper.cpp`(`computeDeviceType()`)
- USB HID Usage Tables / HUTRR34(多点触摸 digitizer 规范)
- Android 官方:[输入设备配置文件(IDC)](https://source.android.com/docs/core/interaction/input/input-device-configuration-files)

## 9.5 【已解决】三星「是否连入外接键盘」弹窗

### 症状

手机连上板子后,**点任何输入框都弹**「是否连入外接键盘」,把输入法挡住,
导致**手动输入也没法用**。更严重的是:**断开蓝牙、卸载 App 之后还会弹**。

### 真正的根因(两个叠加)

**根因 A:描述符里声明了键盘集合。**
只要 HID 描述符里有 Keyboard 集合,三星就认为接入了外接键盘并弹窗。

**根因 B:描述符 Collection 不配对(这是我改出来的 bug)。**
用脚本把键盘/鼠标集合改成 `#if` 条件编译时,把鼠标块第二个 `0xC0`
(End Collection)**留在了 `#endif` 外面**,导致:

```
Collection 开(0xA1) = 2 个
Collection 闭(0xC0) = 3 个   ← 多一个
```

描述符非法 → **Android 的 HID 层直接拒绝连接**,表现为:

```
蓝牙 HID 服务:  HID connection state=0   ← 没连上
                HOGP connection state=2  ← 蓝牙链路正常
内核:           连 /dev/input/eventX 都不存在
板子:           hid = True(它只知道发出了,不知道对面收没收到)
```

而且这个**残留记录卡在系统蓝牙服务里**,所以断蓝牙、卸 App 都没用。

### 解决

1. **把键盘/多媒体/鼠标集合从描述符里去掉**(改成 `#if` 开关,默认关闭)。
   `hid_report_map.h` 里 `HID_INCLUDE_KEYBOARD` / `HID_INCLUDE_CONSUMER` /
   `HID_INCLUDE_MOUSE` 都设 0,只保留触摸屏。
   → 设备分类从 `TOUCH | TOUCH_MT | KEYBOARD | EXTERNAL` 变成
     **`TOUCH | TOUCH_MT | EXTERNAL`**,不再触发弹窗。

2. **重新配对**(清掉系统里缓存的旧描述符):
   蓝牙设置 → 点设备右侧齿轮 → 取消配对 → 重新配对。

### 校验方法(必做)

改完描述符**一定要逐字节验证 Collection 配对**,光能编译不够:

```bash
python3 tools/verify_descriptor.py     # 检查开闭配对 + 关键字段位置
```

期望输出 `Collection: 开=2 闭=2 深度=0 ✅ 配对正确`。

### 教训

> **"能编译" ≠ "描述符合法"。** Collection 不配对这种错误编译期完全发现不了,
> 只会表现为"蓝牙连着但设备不存在"这种极难定位的现象。
> 现在把它变成了自动校验。



---

## 10. 最新能力一览

> 详细用法见 [`docs/使用说明.md`](docs/使用说明.md)。这里只列"有什么"。

### 脚本

| 能力 | 说明 |
|---|---|
| 12 条动作指令 | 点击/双击/长按/直线滑/曲线滑/双指/捏合/按键/多媒体/等待/重复/跳转 |
| **备注参数** | `T 0.5 0.5 60 点我的票` → 日志显示「点击:点我的票」 |
| **人类语言日志** | `[1/9] 点击:点我的票` 而不是一串坐标 |
| **`waitfor`** | 等到页面出现关键字再继续(替代写死的 wait) |
| **`ifpage`/`else`/`endif`** | 按当前页面分支执行 |

### 连接与运维

| 能力 | 说明 |
|---|---|
| **自动发现** | UDP 广播找板子,换网络不用手填 IP |
| **热点兜底** | 连不上任何 WiFi 时自己开热点配网 |
| **OTA 无线升级** | 不用插 USB;流程不会丢 |
| **控制中心** | 多设备台账、固件/APK 分发、黑白名单、网页看板;APK 与每个渠道的固件只保留最近 5 个版本(上传后、启动时自动清理,被策略指定的固件不删) |
| **后台一键构建** | 后台「🛠 一键构建」页:选分支、填版本号,在服务器上编 App / OTA 固件,看实时日志;编好先进构建记录,检查后点「发布」才下发(和手工上传走同一套入库 + 保留策略) |
| **后台远程升级固件** | 控制中心勾选板子点「⬆ 升级固件」,命令跟着板子心跳下去;跑流程 / 15 分钟内有定时任务时推迟,下载后核对 sha256 才刷。可给板子开「自动升级」:发布新固件后空闲时自己升(固件 ≥ 1.4.0) |
| **USB 刷机(网页)** | 后台「🔌 USB 刷机」页:板子插在电脑上,Chrome / Edge 直接刷;「全新板子」整片擦除,「保留数据」不动 WiFi 和流程 |
| **板子名字 + 备注** | 蓝牙名 / 热点名 / 后台名字都是 `ESP32-S3_XXXX`(MAC 最后 4 位,不会重名);备注(如「大麦1号」)在后台 / App / 板子网页都能改,以服务器为准、不能重名(固件 ≥ 1.5.0) |

### 手机 App

| 能力 | 说明 |
|---|---|
| 可视化编排 | 卡片式编辑,拖滑块调参数,和文本双向同步 |
| **坐标拾取器** | 悬浮窗点 📐,在真实界面点哪儿取哪儿 |
| **页面识别** | 悬浮窗显示"现在在哪个 App 的哪个页面" |
| **句柄复制** | 悬浮窗点 🔍 复制页面句柄 |
| 定时 / 频率 | 指定时刻 / 倒计时;频率带**时长控制** |
| 流程复制 | 复制出的流程完全独立,默认暂停 |
| 启动提醒 | 使用前须知;App 更新弹窗**每天一次** |

---

## 11. 修掉的隐蔽 bug(值得记住)

| bug | 后果 | 教训 |
|---|---|---|
| `NetManager` 有两个实例 | AP 模式状态报 `OFF / 0.0.0.0` | 单例要写对 |
| `scriptToText` 不输出备注 | App/网页一编辑,备注就丢 | 序列化/反序列化要成对 |
| 定时执行不锁界面 | 注入点击可能落到自己界面上 | 执行时要挡住 UI |
| WiFi 配错污染 NVS | 板子重启后彻底失联 | **连上了再保存** |
| 输入法自动收起 | 打字时键盘自己消失 | `SOFT_INPUT_ADJUST_NOTHING` 的坑 |
| 悬浮窗重复创建 | 屏幕上出现两套 | Activity 重建要单例 |
| 面板加了点击事件 | 点了没反应 | 面板是 `FLAG_NOT_TOUCHABLE` |
| 在线判定 5 分钟 | 板子 1 小时心跳 → 永远显示离线 | 阈值要匹配间隔 |
| 手机只在启动时报到(6 小时一次) | App 一直开着,设备管理却显示手机离线 | 现在任何带 `deviceId` 的 App 请求都刷新在线,App 另有 2 分钟心跳 |
| 接口前缀改一半 | 看板满屏 404 | 前后端一起改 + 禁缓存 |
| **MIUI 强停清无障碍** | 页面识别整个失效 | 别 `force-stop`,重启手机恢复 |

---


## 实施记录(全部文档)

> `docs/` 下 32 篇。**想直接用 → 只看第一行那篇。**

### 🚀 入门

- [`docs/悬浮窗日志与频率挪位.md`](docs/悬浮窗日志与频率挪位.md) —— **日志解耦 + 频率进定时页**
- [`docs/滑动验证码处理.md`](docs/滑动验证码处理.md) —— **轮询代替死等 + goto 跨循环**
- [`docs/成功统计.md`](docs/成功统计.md) —— **按板子+流程聚合的成功次数**
- [`docs/出现支付宝后通知服务器.md`](docs/出现支付宝后通知服务器.md) —— **notify 指令 + 服务器崩溃事故**
- [`docs/定时没触发-只调度一个.md`](docs/定时没触发-只调度一个.md) —— **调度器只处理一个流程**
- [`docs/repeat循环怎么用.md`](docs/repeat循环怎么用.md) —— **repeat…endloop 循环(修了死循环)**
- [`docs/守卫用应用不用页面词.md`](docs/守卫用应用不用页面词.md) —— **为什么我的守卫版"不动了"**
- [`docs/ifpage安全版流程.md`](docs/ifpage安全版流程.md) —— **嵌套 ifpage + 栈溢出排查**
- [`docs/超时后继续执行是什么.md`](docs/超时后继续执行是什么.md) —— **超时不是时间不够**
- [`docs/流程执行日志在哪看.md`](docs/流程执行日志在哪看.md) —— **四个入口 + 日志怎么读**
- [`docs/看板在线判定与公网IP.md`](docs/看板在线判定与公网IP.md) —— **在线判定 / 公网IP定位 / APK上传**
- [`docs/发布固件到服务器.md`](docs/发布固件到服务器.md) —— **网页直接下载固件/脚本**
- [`docs/板子热点下怎么同步数据.md`](docs/板子热点下怎么同步数据.md) —— **App 代板子上报**
- [`docs/代码备份到服务器.md`](docs/代码备份到服务器.md) —— **git 仓库 + 一条命令备份**
- [`docs/流程同步规格.md`](docs/流程同步规格.md) —— **⭐ 流程同步的权威规格(以此为准)**
- [`docs/App流程列表缓存.md`](docs/App流程列表缓存.md) —— **切页签秒开 + 手动刷新**
- [`docs/流程管理架构.md`](docs/流程管理架构.md) —— **服务器为权威 + App 中转(第⑥项)**
- [`docs/后台Vue重构.md`](docs/后台Vue重构.md) —— **后台改 Vue 3(含两个大坑)**
- [`docs/需求实现度审查.md`](docs/需求实现度审查.md) —— **11 条构想逐条审查 + 问题清单**
- [`docs/灯怎么关怎么改颜色.md`](docs/灯怎么关怎么改颜色.md) —— **五条控制链路 / 关灯 / 指定颜色**
- [`docs/日志分页.md`](docs/日志分页.md) —— **操作审计 / 事件明细分页,每页 50 条**
- [`docs/后台重构与LED对号.md`](docs/后台重构与LED对号.md) —— **左侧菜单多页面 + 点灯对号**
- [`docs/多连接方式-验证总表.md`](docs/多连接方式-验证总表.md) —— **一页看清验证状态**
- [`docs/多连接方式-第四步-BLE吞吐瓶颈.md`](docs/多连接方式-第四步-BLE吞吐瓶颈.md) —— **吞吐瓶颈根因与复盘(已解决)**
- [`docs/多连接方式-第四步-验证与踩坑.md`](docs/多连接方式-第四步-验证与踩坑.md) —— **三个真 bug(含 OOM 崩溃)**
- [`docs/多连接方式-第三步-App蓝牙传输层.md`](docs/多连接方式-第三步-App蓝牙传输层.md) —— **App 侧:不用 WiFi 也能读写板子**
- [`docs/多连接方式-第二步-BLE控制通道.md`](docs/多连接方式-第二步-BLE控制通道.md) —— **固件侧:蓝牙也能传数据了**
- [`docs/多连接方式-第一步-自动检测.md`](docs/多连接方式-第一步-自动检测.md) —— **两条链路的区别 + 三种连接检测**
- [`docs/三星手机安装配置.md`](docs/三星手机安装配置.md) —— **Android 14+ 必崩的权限坑**
- [`docs/新板N16R8烧录记录.md`](docs/新板N16R8烧录记录.md) —— **第二块板实测 + 公司网络方案**
- [`docs/新板子怎么烧录.md`](docs/新板子怎么烧录.md) —— **新板子从零到跑起来**
- [`docs/使用说明.md`](docs/使用说明.md) —— **⭐ 部署 + 配置 + 脚本语法 + 排障,一篇搞定**

### 📡 固件

- [`docs/OTA无线升级.md`](docs/OTA无线升级.md) —— 双分区 OTA,不用插 USB
- [`docs/板子自动发现.md`](docs/板子自动发现.md) —— UDP 广播找板子
- [`docs/首次配网与AP兜底.md`](docs/首次配网与AP兜底.md) —— 热点配网全流程
- [`docs/键盘弹窗-最终结论.md`](docs/键盘弹窗-最终结论.md) —— **Appearance 必须是 0x03C5**

### 📱 手机 App

- [`docs/指令库与可视化编排.md`](docs/指令库与可视化编排.md) —— 卡片式编辑流程
- [`docs/坐标拾取器.md`](docs/坐标拾取器.md) —— 在真实界面取点
- [`docs/坐标精度与文本往返.md`](docs/坐标精度与文本往返.md) —— 为什么 `0.923` 和 `0.9230` 一样
- [`docs/定时状态管理.md`](docs/定时状态管理.md) —— 定时、过期、唯一开启
- [`docs/过期流程与目标App校验.md`](docs/过期流程与目标App校验.md) —— 过期禁止开启 + App 不匹配提示
- [`docs/App启动提醒.md`](docs/App启动提醒.md) —— 使用前须知 + **更新弹窗每天一次**
- [`docs/执行时不息屏.md`](docs/执行时不息屏.md) —— **息屏时间最大只有分钟级怎么办**
- [`docs/设置与帮助分离.md`](docs/设置与帮助分离.md) —— **设置只放开关,说明挪到帮助**
- [`docs/流程备注怎么加.md`](docs/流程备注怎么加.md) —— **备注的两种写法**
- [`docs/悬浮窗把柄竖排.md`](docs/悬浮窗把柄竖排.md) —— 5 个按钮的竖排布局
- [`docs/悬浮窗让路机制.md`](docs/悬浮窗让路机制.md) —— 🙈 让路 15 秒
- [`docs/悬浮窗倒计时与流程排序.md`](docs/悬浮窗倒计时与流程排序.md) —— 1 秒一跳 + 四级排序
- [`docs/悬浮窗重复与参数单位.md`](docs/悬浮窗重复与参数单位.md) —— 单例修复
- [`docs/数据存放位置与重装影响.md`](docs/数据存放位置与重装影响.md) —— 什么会丢什么不会
- [`docs/保存后自动刷新与广告让路.md`](docs/保存后自动刷新与广告让路.md)
- [`docs/指令插入改为空模板.md`](docs/指令插入改为空模板.md)
- [`docs/流程内容框提示文字.md`](docs/流程内容框提示文字.md)
- [`docs/编辑流程连接失败.md`](docs/编辑流程连接失败.md) —— **跑流程时板子不应答(80% 实测失败率)**
- [`docs/指令快捷条改成多行.md`](docs/指令快捷条改成多行.md) —— **不再横向滑动**
- [`docs/编辑流程键盘挡编辑框.md`](docs/编辑流程键盘挡编辑框.md) —— **键盘弹出时编辑框被挤没**
- [`docs/输入法自动收起-修复.md`](docs/输入法自动收起-修复.md)
- [`docs/多手机同步操作-可行性.md`](docs/多手机同步操作-可行性.md) —— 最多同时连 3 台

### 🎯 页面识别

- [`docs/页面识别调研.md`](docs/页面识别调研.md) —— **Android 到底能不能知道当前页面**
- [`docs/选词面板改进.md`](docs/选词面板改进.md) —— **读 contentDescription + 可滚动 + ⭐ 排前**
- [`docs/选词面板配色修复.md`](docs/选词面板配色修复.md) —— **深色 App 别用默认 Button**
- [`docs/智能等待怎么用.md`](docs/智能等待怎么用.md) —— **⭐ 挑关键字的完整用法 + 三层根因**
- [`docs/页面识别实现.md`](docs/页面识别实现.md) —— 无障碍服务落地(含 3 个 MIUI 坑)
- [`docs/页面条件指令.md`](docs/页面条件指令.md) —— **`waitfor` / `ifpage` 实测**
- [`docs/waitfor排查与NPE崩溃.md`](docs/waitfor排查与NPE崩溃.md) —— **一个 NPE 让页面识别全废的排查全程**
- [`docs/waitfor演示流程.md`](docs/waitfor演示流程.md) —— **演示流程 + 两个严重序列化 bug**

### ☁️ 控制中心(服务器)

- [`docs/数据库与账号系统.md`](docs/数据库与账号系统.md) —— **MySQL 安装 + 账号体系**(含密码策略鸡生蛋问题)
- [`docs/控制中心接入.md`](docs/控制中心接入.md) —— 固件心跳 + App 接入
- [`docs/流程与定时上报.md`](docs/流程与定时上报.md) —— **为什么流程名和定时要分开上报**
- [`docs/网页版对照迭代.md`](docs/网页版对照迭代.md) —— 板子自带网页控制台
- [`docs/服务端方案设计.md`](docs/服务端方案设计.md) ⚠️ **已过时**(JSON 版,见文首提示)
- [`docs/服务端部署记录.md`](docs/服务端部署记录.md) ⚠️ **已过时**(JSON 版,见文首提示)

**后台一键构建**(`server/src/builder.js`、`server/tools/tp-build.sh`):

- 首次使用先在服务器上装工具链(可重复执行):`bash server/tools/setup-build-env.sh`
  → `/opt/tp-build/` 下 JDK 17、Android build-tools 35 / android-35、Python 3.11 + PlatformIO 6.1.16
  (espressif32 7.1.3、Arduino 框架 4.20017.260907)、固件凭据 `secrets.h`(权限 600),另建 2G swap。
- App:versionCode 自动取号(线上最大 + 1),versionName 页面填;固件:版本号、渠道页面填。版本号只写进构建副本,仓库代码不动。
- 代码从 `/root/esp32-s3-hid` 用 `git archive` 只读导出(不切分支);同一时间只跑一个构建,
  构建进程在 systemd scope 里限内存 1000M、nice/ionice 降优先级;服务重启时正在跑的构建标记失败。
- 每类只保留最近 5 次构建的日志和产物;发布后的文件在 APK / 固件列表里另存一份。
- 固件只编 `ble` 环境;除了 OTA 包,还收集启动程序 / 分区表 / boot_app0 和 `flash.json`(地址与参数),
  发布时跟着固件存到 `data/firmware/<文件>.parts/`,给「USB 刷机」用。下载页 `/download/` 的整片烧录包仍用 Mac 上的 `tools/release-firmware.sh`。

**后台远程升级固件**(`server/src/ota.js`、固件 `src/common/net_manager.cpp` 的 `otaTick`):

- 服务端够不到内网的板子,命令只能等**板子自己的心跳**(每 2 分钟)捎回去 ⇒ 板子必须连着能上外网的 WiFi;
  App 代报的状态不算(蓝牙通道不传固件)。
- 控制中心:勾选板子 →「⬆ 升级固件」选版本;「🔁 开自动升级」后,板子所在渠道发布了更新的版本
  (或升级策略指定了版本)就自动升。「固件升级」列显示状态:等板子心跳 / 推迟 / 已下发 / 已升级 / 失败,可取消。
- 安全:服务端看到板子在跑流程、或 15 分钟内(到 3 分钟前)有「指定时刻」定时任务就推迟;板子收到后自己再查一遍
  (流程在跑 / 排队 / 频率循环开着 ⇒ 报「推迟」);下载时边写边算 sha256,大小或 sha256 不对就丢弃,原固件照常跑;
  下发 10 分钟没升上来判失败;手动命令 24 小时没机会执行就作废;自动升级失败过的版本不再自动重试(重新开一次开关可再试)。
  「倒计时」模式的定时任务板子不上报开始时间,服务端挡不住,只能靠板子那一层。
- 升级策略「永不升级」的板子不接受远程升级;板子固件比渠道最新还新时不再提示「降级」(指定版本除外)。
- **老固件(< 1.4.0)认不得远程命令**:后台标「固件太老,不支持远程」。先在 App 里点一次「升级板子固件」,
  或用「USB 刷机」的「保留数据」模式刷到 1.4.0 以上,之后就能远程升级。

**USB 刷机**(后台「🔌 USB 刷机」页,`server/public/usbflash.js` + `vendor/esptool-js`):

- 用 **Chrome 或 Edge**(Safari / 火狐没有 Web Serial)。后台是 http 地址,浏览器默认不开放串口,一次性设置:
  地址栏打开 `chrome://flags/#unsafely-treat-insecure-origin-as-secure`(Edge 是 `edge://flags/#…`),
  填 `http://120.55.183.206`,选 Enabled,重启浏览器。
- 选固件(一键构建出的才行:已发布的,或构建成功还没发布的)→ 选模式 → 点开始,弹出的串口列表里选板子
  (CH343 / USB Single Serial)。写 4 段:0x0 启动程序、0x8000 分区表、0xe000 boot_app0、0x10000 固件,每段写完让芯片算 MD5 核对。
- 「全新板子」先整片擦除;「保留数据」不碰 0x9000 的配置区和流程分区(WiFi、流程、定时都在)。
  连不上时:按住 BOOT,点一下 RST,松开 BOOT 再点开始。
- 真机脚本 `tests/hw/usbflash_node.mjs` 跑的是同一份刷机代码(Node 里把 Web Serial 换成 `serialport` 包)。

**板子名字和备注**(`server/src/remark.js`、固件 `net_manager.cpp` 的「板子名字 + 备注」一节):

- **名字**:固件按 WiFi MAC 最后两字节生成 `ESP32-S3_XXXX`(如 MAC `…:09:8c` ⇒ `ESP32-S3_098C`),
  蓝牙名、配网热点名、心跳上报的名字都是它,不能改。1.5.0 以前所有板子蓝牙都叫 `ESP32S3 Touch HID`、热点叫 `ESP32-Touch-XXXX`。
  已经配对过的手机可能还显示旧名字,在系统蓝牙里「取消配对」再配一次就是新名字(App 按「名字含 esp32」认板子,不影响使用)。
- **备注**:最多 20 个字,不能和别的板子重名(不分大小写);不能有引号、反斜杠、尖括号、换行。
  **以服务器为准**,三个地方都能改,都要先过服务器查重:
  - 后台:控制中心「名字 / MAC」列的 ✏️,或板子详情里改;板子下次心跳(最多 2 分钟)同步。
  - App:状态卡第一行「板子 …  ✏️ 改备注」;服务器查过重后 App 立刻写进板子(WiFi / 蓝牙都行)。
  - 板子网页:「系统操作」里的「板子备注」;板子转给服务器查重。服务器连不上时先存在板子上、标「备注待同步」,
    下次心跳(或 App 代报)再查重 —— 重名就不采用,板子改回服务器那份,后台在这块板子上标「⚠ 板子上改的「…」没采用」。
- 备注不进蓝牙名:广播包里留给名字的只有 20 字节,中文一个字 3 字节,放不下。
- 后台的成功统计 / 事件明细也显示「名字(备注)」。

### 📋 调研 / 事故

- [`docs/需求分析与风险评估.md`](docs/需求分析与风险评估.md) —— 最开始的可行性分析
- [`docs/注入点击误删流程-事故与修复.md`](docs/注入点击误删流程-事故与修复.md) —— **事故报告(App 挡住乱点)**
- [`docs/事故-误删流程与恢复.md`](docs/事故-误删流程与恢复.md) —— **事故报告(误删 + 从 flash 恢复全过程)**
- [`docs/事故-注入点击差点清空所有流程.md`](docs/事故-注入点击差点清空所有流程.md) —— **事故报告(注入点击打到自己的界面)**

### 🗄 历史存档 `docs/archive/`

阶段性汇报,保留只为追溯:

- `P0-频率实测.md` · `P1-P3-进展.md` · `P4-P9-完成报告.md`
- `键盘弹窗-调查结论.md` · `键盘弹窗-根因找到.md`(已被「最终结论」取代)
