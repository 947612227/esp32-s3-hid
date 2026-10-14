# 无板方案(adb-only)—— 不用买板子,插上 USB 就能点屏幕

手机开 **USB 调试**,用 adb 直接注入点击/长按/滑动。**零硬件成本**,你现在这台三星已经能跑。

> ⚠️ **用途声明**:本项目是**通用 Android 自动化/输入注入测试工具**,用于学习输入子系统、
> 做 UI 自动化测试与设备兼容性验证。**不包含任何特定 App 的坐标、流程或适配**,
> 也不用于绕过任何平台的风控或用户协议。请自行确认使用场景合法合规。

---

## 与有板方案(ESP32-S3 HID)的区别

这个工程里现在有**两条独立的路**,不要混:

| | **无板方案**(本目录 `adb-only/`) | **有板方案**(上一级 `src/`) |
|---|---|---|
| 硬件 | **不需要**,一根 USB 线 | ESP32-S3 开发板 |
| 注入方式 | adb → Android shell → input 命令 | ESP32 做成 HID 设备,手机当 USB Host / BLE 主机 |
| 需要 USB 调试 | ✅ 需要,且电脑要授权 | ❌ 不需要 |
| 需要电脑在场 | ✅ 需要(或无线调试) | ❌ 可以脱机独立跑 |
| 软件痕迹 | 有:USB 调试状态、`input` 命令 | 较轻:设备表现为外接 HID |
| 延迟 | 每次动作几十~几百 ms(要起 shell) | 毫秒级 |
| 能感知屏幕吗 | ✅ 能:截图 + UI 树 | ❌ 不能(纯注入) |
| 免 root | ✅ | ✅ |
| 免装 App | ✅ | ✅ |
| 适合 | 快速验证、调试、写测试脚本 | 长期运行、无线、无痕、要低延迟 |

**关键洞察**:两条路的"感知"部分是一样的(都是 adb 截图/UI 树),**只有"注入"那一步不同**。
所以本目录把注入抽成了 `drivers.py` 里的 `Driver` 接口 —— 以后接上板子,只需新增一个
`HidDriver` 实现,**上层的目标解析和脚本一行都不用改**。

> 建议的开发顺序:先用本目录把"要点哪里、什么时候点"跑通(零成本),确定流程稳定后,
> 再决定要不要换成板子做长期运行。反过来先买板子容易在流程没定型时白折腾。

---

## 目录结构

```
adb-only/
├── click.py              # CLI 入口(所有命令都从这里进)
├── noboard/
│   ├── adb.py            # adb 驱动:设备/截图/UI树/屏幕信息/输入注入
│   ├── targets.py        # UI 树解析 + 目标→坐标 + 坐标写法解析
│   ├── drivers.py        # 注入层抽象:AdbDriver / DryRunDriver / HidDriver(走板子)
│   └── runner.py         # 动作层:找目标、发动作、跑脚本
├── scripts/
│   └── demo.txt          # 示例脚本(命令语法演示)
└── shots/                # 截图输出(运行时生成)
```

纯标准库实现,**不需要 pip 安装任何东西**。

---

## 准备工作

1. 手机 **设置 → 开发者选项 → USB 调试** 打开(开发者选项需连点"版本号"7 次);
2. USB 线连电脑(必须是**能传数据**的线,不是只充电线);
3. 手机上弹出"允许 USB 调试吗?" → 勾选**一律允许** → 确定;
4. 验证:

```bash
cd adb-only
python3 click.py devices
```

看到 `xxxx device` 就成功了。如果列表为空,按提示逐项排查(线/开关/授权)。

> adb 路径本工具会自动探测(常见 SDK 目录、Homebrew、PATH 都覆盖),也可以用
> `--adb /path/to/adb` 或环境变量 `ADB=...` 指定。

---

## 快速上手

**第一步:看当前界面能点什么**(这一步最关键,它会打印每个可点元素的中心坐标)

```bash
python3 click.py ui
```

输出示例(实测):

```
当前界面可点元素(1 个):
  中心坐标           bounds                     元素
  540,1608       (202, 1552, 878, 1665)     确认并知悉
```

**第二步:先空跑确认坐标,再真点**(强烈建议养成习惯)

```bash
python3 click.py --dry-run tap 确认并知悉     # 只解析+打印,不注入
python3 click.py tap 确认并知悉               # 真的点
```

**第三步:按坐标点 / 滑动**

```bash
python3 click.py tap 540,1608              # 绝对像素
python3 click.py tap 50%,69%               # 百分比(推荐,换分辨率不用改)
python3 click.py swipe 50%,75% 50%,30% --ms 300 # 上滑,300ms(命令行用 --ms,脚本里直接写数字)
python3 click.py long 50%,50% --ms 800          # 长按 800ms
```

**第四步:一条命令串多个动作**(`;` 分隔,适合"等界面渲染后再点")

```bash
python3 click.py exec "wait 1.5 ; tap 确认并知悉 ; wait 1 ; tap 50%,40%"
```

---

## 目标(要点哪里)的写法

`tap` / `long` / `swipe` 的目标参数支持下面几种,可以混用:

| 写法 | 含义 | 例子 |
|---|---|---|
| 文字 | UI 树里文本**精确**匹配,匹配不到自动退化为包含匹配 | `tap 确认并知悉` |
| `text:xx` | 文本**包含**匹配 | `tap text:确认` |
| `id:xx` | resource-id **后缀**匹配 | `tap id:btn_confirm` |
| `desc:xx` | content-desc 包含匹配 | `tap desc:返回` |
| `x,y` | 绝对像素坐标 | `tap 540,1608` |
| `x%,y%` | 百分比坐标 | `tap 50%,69%` |
| `center` | 屏幕中心 | `tap center` |

**自动回退到可点击祖先**:文字节点本身通常 `clickable=false`,工具会向上找最近的可点击
祖先并点它的中心 —— 这是"按文字点"能work的关键。

**只找坐标不点击**:`python3 click.py find 确认并知悉` 会直接输出 `540,1608`,
方便你把它写进脚本或硬编码。

---

## 命令与脚本

所有命令和脚本文件共用同一套语法(`runner.py` 的 `exec_line`):

| 命令 | 说明 |
|---|---|
| `tap <目标> [次数]` | 点击,可选连点次数 |
| `long <目标> [ms]` | 长按,默认 800ms |
| `swipe <起点> <终点> [ms]` | 滑动,默认 300ms |
| `text <内容>` | 输入 **ASCII** 文本(中文见下方限制) |
| `key <键名>` | 按键:`HOME` / `BACK` / `ENTER` / `KEYCODE_xxx` 或数字 |
| `wait <秒>` | 等待 |
| `ui` | 打印当前界面可点元素 |
| `shot [文件名]` | 截图到 `shots/` |

跑脚本:

```bash
python3 click.py --dry-run run scripts/demo.txt        # 先空跑
python3 click.py run scripts/demo.txt                  # 真跑
python3 click.py run scripts/demo.txt --loop 5 --interval 2   # 循环 5 轮,间隔 2s
```

CLI 参数:

| 参数 | 作用 |
|---|---|
| `--dry-run` | **只解析坐标不注入**,调试必备 |
| `--serial/-s` | 指定设备(多台设备时必需) |
| `--adb` | 指定 adb 路径 |
| `--quiet/-q` | 少打印 |
| `--shot-dir` | 截图目录 |

---

## 已知限制(都很真实,提前知道能省时间)

### 1. UI 树可能是空的 → 只能按坐标

纯自绘 / WebView / 游戏引擎渲染的界面,`uiautomator dump` 拿不到节点。
**实测例子**:某个票务 App 的弹层页面,**整屏只有 14 个节点、只有 1 个 clickable** ——
这种情况 `tap 某个文字` 会失败,只能 `tap 540,1608` 用坐标。

**怎么判断**:跑 `python3 click.py ui`。如果输出"没有任何可点元素",就走坐标路线。
**怎么找坐标**:`python3 click.py shot` 截图,用系统预览工具读像素坐标,或者用百分比估算。

### 2. `input text` 不支持中文

adb 的 `input text` 只能发 ASCII。要输入中文有两个办法:
- 手机装 **ADBKeyboard** 输入法,切成它之后用 IME 广播输入(网上有现成方案);
- 或者用 `key` 发按键,配合粘贴板(需要额外工具)。

本工具目前只做 ASCII,`text()` 里对空格做了 `%s` 转义。

### 3. 每次动作都要起一次 shell,有延迟

`input tap` 是启动一个进程发事件,单次几十到几百 ms。高频连点会明显慢。
如果目标场景对时序敏感,**这正是换 HID 板子的理由**(毫秒级)。

### 4. 息屏时事件会被丢弃

工具在每次动作前会检测亮屏状态并尝试唤醒(`adb.wake()`),但如果你手机上锁了,
`input` 事件无法解锁 —— 需要先手动解锁,或关闭锁屏密码(测试机上常用)。

### 5. 软件痕迹

USB 调试状态、adb 授权、`input` 命令都是可被检测的信号。如果目标环境会检查这些,
无板方案不合适,应该换 HID 板子。**这是两条路本质的取舍,不是能"优化"掉的东西。**

### 6. `FLAG_SECURE` 页面截不了图

截图会返回空,工具会报错。这种情况下只能用坐标盲点。

---

## 接 HID 板子:`--driver hid`

找控件、截图照旧用 adb,**只有"把动作发出去"交给板子**:板子调自己的网页接口
`/api/gesture`(点击/长按/滑动)、`/api/control`(HOME/返回/最近任务),
手机收到的是外接触摸屏的真实触摸,不是 `input` 命令。

```bash
# 电脑和板子在同一个网络
python3 click.py --driver hid --board 192.168.1.50 tap 确认并知悉

# 板子连在手机热点上、电脑够不着 ⇒ 加 --relay,经手机上的 curl 转发
python3 click.py --driver hid --board 10.207.182.250 --relay run scripts/demo.txt
```

限制(都由固件决定):

- `text` 不支持:键盘接口默认编译关闭(`HID_INCLUDE_KEYBOARD=0`),而且中文输入法会把按键变成候选词;
- `key` 只支持 `HOME` / `BACK` / `APP_SWITCH` —— 板子是**点屏幕底部三键导航栏**实现的,
  手机要用三键导航;电源/音量走 HID 多媒体接口,默认也编译关闭,所以不映射;
- 板子返回非 200(如 `409 HID 未就绪`)会直接报错,不会静默失败。

**上层的目标解析(`targets.py`)、动作层(`runner.py`)、脚本语法全都不用改** ——
这就是当初把 driver 抽出来的意义。

测试:`python3 tests/adb_only/test_hid_driver.py`(单元,不需要设备)、
`bash tests/adb_only/test_hid_driver_device.sh`(真机)。

---

## 参考

- Android 官方:[`input` 命令](https://developer.android.com/tools/adb#input)(tap/swipe/text/keyevent)
- Android 官方:[UI Automator](https://developer.android.com/training/testing/other-components/ui-automator)(`uiautomator dump` 的输出格式)
- 有板方案(ESP32-S3 HID)请见上一级 [`README.md`](../README.md)
