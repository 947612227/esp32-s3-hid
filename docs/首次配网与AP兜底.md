# 首次配网:热点兜底 + App 引导

# 首次配网:完整答案

## 先说结论(实测过的)

**板子出厂就编译进去了你家 WiFi**(`secrets.h`)—— 所以在**你家**插电即用,**不需要配网**。

换到**别的网络**时:
```
板子上电
  ├─ 试 NVS 里存的凭据(你在 App 里配过的)
  ├─ 试编译期默认凭据(secrets.h 里的)
  └─ 都失败 → 开热点 ESP32-Touch-XXXX / 12345678 @ 192.168.4.1
                    ↓
             手机连这个热点 → App 自动发现板子 → App 里填新 WiFi
```

**这条路我端到端跑通了**(见下面实测记录)。

---

## 你问的鸡生蛋问题

> 板子通电后怎么首次配 WiFi?用 App 配也要先知道 IP 吧?

**对,这是个真实的死循环。** 但固件早就有**热点兜底**,只是"隐性知识"太多没人知道:

```
板子上电
  ├─ 试连已存的 WiFi
  │    ├─ 成功 → STA 模式,IP 由路由器分配(192.168.x.y)
  │    └─ 失败(含**首次没配过**)↓↓↓
  └─ 开热点:ESP32-Touch-XXXX   密码 12345678
              访问地址 http://192.168.4.1/
```

**所以路径是通的**,只是要同时知道三件事:热点名格式、密码、`192.168.4.1`。

---

## 这次补了什么

### ① 固件:热点模式也响应自动发现(关键漏洞)

原来 UDP 发现只在 **STA 模式**跑:

```cpp
if (_started && staConnected()) { discoverBegin(); discoverLoop(); }
```

**热点模式下不跑** → 手机连着板子热点时,**App 完全找不到它** → 引导就断了。

改成:

```cpp
if (_started && (staConnected() || _ap)) { discoverBegin(); discoverLoop(); }
```

### ② App:自动试热点地址 `192.168.4.1`

`ESP32` 的 softAP 地址是固定的 `192.168.4.1`。所以 App 在"局域网广播找不到"之后,
**直接拿这个地址试一次** —— 不用申请定位权限去读手机当前 SSID。

### ③ App:首次配网引导(把隐性知识写出来)

状态页 →「🔍 自动查找板子」找不到时,或**设置 → 板子连不上 WiFi?** 会弹出:

```
首次使用 / 换了网络?

① 手机连板子的热点
   设置 → WLAN,找 ESP32-Touch-XXXX
   (XXXX 是板子 MAC 后两位),密码:12345678

② 回到本 App
   点「🔍 自动查找板子」—— 这时能连上(板子在 192.168.4.1)

③ 在 App 里填 WiFi
   状态页 → 设置 → 给板子配网
   连上后板子会自动切回你的 WiFi(热点关掉)

④ 手机切回你家 WiFi
   App 会自动发现板子在新网络里的 IP

                       [去 WLAN 设置]  [知道了]
```

### ④ 新增 `POST /api/system?op=forgetwifi`

忘了当前 WiFi → 重启进热点模式。**换网络/搬家时用**,不用重新烧录。

```bash
curl -X POST "http://192.168.5.37/api/system?op=forgetwifi"
```

---

## 完整流程图

```
【首次】板子上电 → 连不上 → 开热点 ESP32-Touch-XXXX
                              ↓
         手机连热点 → App 广播/试 192.168.4.1 → 连上
                              ↓
         App 里填 WiFi 名+密码 → 板子切到 STA → 热点关
                              ↓
         手机切回自家 WiFi → App 自动发现新 IP(不用手填)

【换网络】App 点「恢复出厂」或调 op=forgetwifi
         → 回到上面第一步
```

---

---

# ✅ AP 路径已端到端实测通过(2026-10-05)

## 实测过程

| 步骤 | 结果 |
|---|---|
| 备份 NVS(含 WiFi 凭据) | ✅ 20480 字节,可 `write_flash 0x9000` 恢复 |
| `op=forgetwifi` 清配置 | ✅ `ssid NOT_FOUND` |
| 临时把编译期 SSID 改成不存在的名字 + 烧录 | ✅ 板子不再连任何 WiFi |
| 手机扫描 | ✅ **找到 `ESP32-Touch-5E40`** |
| 手机连该热点(密码 12345678) | ✅ 手机拿到 `192.168.4.2` |
| **启动 App** | ✅ **自动连上板子** |
| 用完恢复 | ✅ 还原 secrets.h + NVS,板子回到 STA |

## 顺带抓到的两个真 bug

### bug 1:`NetManager` 有两个实例(严重)

```cpp
static NetManager s_net;          // handleStatus 读这个
NetManager &netManager() {
  static NetManager inst;         // begin() 在这个上开热点
  return inst;
}
```

**两个不同的对象。** 于是热点开在 `inst` 上、`s_net._ap` 永远是 false →
**AP 模式下 `/api/status` 报 `mode=OFF, ip=0.0.0.0`**(实测就是这么显示的)。

STA 模式下因为 `WiFi.status()` 是全局的,**侥幸看不出来** —— 埋得很深。

**修法**:`NetManager &netManager() { return s_net; }`

### bug 2:`Serial` 不在这块 USB 口上

`platformio.ini` 里:

```
-DARDUINO_USB_MODE=0         → 原生 USB 交给 TinyUSB
-DARDUINO_USB_CDC_ON_BOOT=0  → 原生 USB 上不开 CDC
```

所以 **Arduino 的 `Serial` 走 UART0(CH343 那个口)**,
原生 USB 口只有 ESP-IDF 自己的日志(ROM banner、Preferences 报错)。

排查时我一度以为板子"卡死"—— 其实是**看错口了**。这个坑记下来。

## 实测修正:AP 名字

实测是 **`ESP32-Touch-5E40`**(代码里取 `mac[4], mac[5]`)。
板子蓝牙 MAC 是 `ac:a7:04:26:5e:41`,所以 WiFi MAC 的末字节可能不同。
