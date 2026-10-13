# 多连接方式 · 第三步:App 端蓝牙传输层

## 做完什么

**App 现在能通过蓝牙读写板子** —— 不用连板子 WiFi、不用板子有网 ✅

```
控制方式:自动(蓝牙优先)   ← 可切换
[🔵 连接蓝牙控制]  [⚙ 控制方式]
```

实测(三星 S23,WiFi 完全不可达):
```
[ble] 控制命令:STATUS   → 607 字节,分 4 片(MTU 255)   ✅
[ble] 控制命令:LOG      → 982 字节,分 6 片(MTU 255)   ✅
[ble] 控制命令:PAGE com.dsh.touchpad.MainActivity      ✅
```

---

# 一、最省事的做法:**让 BLE 响应和 HTTP 同形**

App 里所有读板子的代码都是这个形状:

```java
board.status(new Board.Cb() {
    public void done(boolean ok, String body) { ...解析 body 的 JSON... }
});
```

如果 BLE 那套返回**不一样的格式**,App 里几十个调用点都要改 ✅

**所以固件那边刻意把响应对齐 HTTP:**

| 命令 | 响应 |
|---|---|
| `RUN n` | `{"ok":true,"message":"已排队执行 大麦 x1"}` |
| `STOP` | `{"ok":true,"message":"已请求停止(当前步骤结束后生效)"}` |
| `SAVE` | `{"ok":true,"message":"已保存 xxx"}` |
| `DEL` | `{"ok":true,"message":"已删除 xxx"}` |
| 出错 | `{"ok":false,"message":"..."}` |

**结果:App 侧的调用代码一行都没改** ✅
只在 `Board.request()` 里加了一层"这条请求能不能走蓝牙"的判断 ✅

---

# 二、HTTP 路径 → BLE 命令的翻译

```java
private static String toBleCmd(String method, String path, String body) {
    if (p.equals("/api/status"))     return "STATUS";
    if (p.equals("/api/scripts"))    return "LIST";
    if (p.equals("/api/logs"))       return "LOG";
    if (p.equals("/api/stop"))       return "STOP";
    if (p.equals("/api/script"))     return "GET " + arg(query, "index");
    if (p.equals("/api/run"))        return "RUN " + arg(query, "index");
    if (p.equals("/api/delete"))     return "DEL " + arg(query, "name");
    if (p.equals("/api/page"))       return "PAGE " + arg(query,"act") + "\n" + arg(query,"text");
    if (p.equals("/api/save"))       return "SAVE -1\n" + arg(query,"name") + "\n" + body;
    return null;   // 其它(改 WiFi 密码 / OTA)只能走 WiFi
}
```

**返回 `null` 的走 WiFi 兜底** —— 蓝牙带宽小,不适合做通用 HTTP 转发 ✅

---

# 三、⚠️ 踩的坑

## ① `/api/page` 的参数在 **query** 里,不在正文里

```java
// pushPage 的写法:
"/api/page?act=" + URLEncoder.encode(act) + "&text=" + ...
```

我一开始读的是 `body` ⇒ 推上去的是 **`PAGE \n`(空的)** ✅
表现:**板子的 waitfor 永远匹配不到** ✅ 但命令看着发出去了,很难发现 ✅

**修**:从 query 里取 `act` / `text` ✅

## ② 缺 `BLUETOOTH_SCAN`

界面直接显示了完整错误(这个设计帮了大忙):

```
蓝牙控制通道:扫描失败:Need android.permission.BLUETOOTH_SCAN permission
for AttributionSource { ... com.dsh.touchpad ... } ScanBinder.registerScanner()
```

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                 android:usesPermissionFlags="neverForLocation" />
```

> `neverForLocation` 声明"扫描不是为了定位" ⇒ **不用再要定位权限** ✅

## ③ 分片:707 字节发了 **37 片**

板子一开始以为 MTU = 23(每片载荷 20 字节)✅ 但安卓那边明明协商到了 **255** ✅

**根因**:我只在 `onSubscribe` 里读了 `connInfo.getMTU()` ✅ 那时候还是默认值 ✅

**修两层**:

```cpp
// ① 真正的时机是 onMTUChange
void onMTUChange(uint16_t mtu, NimBLEConnInfo &info) override {
    _owner->_peerMtu = mtu;
}

// ② 再加一道兜底 —— 回包时实时问一遍,不信缓存
if (_server && _server->getPeerDevices().size() > 0) {
    uint16_t h = _server->getPeerInfo(0).getConnHandle();
    uint16_t m = _server->getPeerMTU(h);
    if (m > 3) _peerMtu = m;
}
```

**效果:707 字节从 37 片 → 4 片** ✅ 快了约 9 倍 ✅

---

# 四、App 侧的实现要点

## 连接(不是配对)

```java
// ⚠️ HID 配对是**系统**做的(HID 必须配对才能注入触摸)
//    我们这里只做 **GATT 客户端连接** —— 是两回事
1. 在已配对列表里找 "ESP32S3 Touch HID"
2. connectGatt(...)  → requestMtu(517) → discoverServices()
3. 找服务 A5C1 → 特征 A5C2(写) / A5C3(通知)
4. setCharacteristicNotification(tx,true) + 写 CCCD
```

**实测握手:**
```
TouchBle: 已配对设备数=1
TouchBle: GATT 已连,发现服务…
TouchBle: 订阅结果 ready=true mtu=255
```

## 分片发送必须**串行**

```java
// ⚠️ BLE 的写是串行的:连着调 writeCharacteristic,后面那些会被丢掉
//    必须等上一片的 onCharacteristicWrite 回调再发下一片
private void sendNext() {
    if (txOff >= txFrame.length) return;   // 发完,等通知回来
    int n = Math.min(chunk, txFrame.length - txOff);
    ... writeNow(part);                     // 回调里再调 sendNext()
}
```

## 超时保护

蓝牙偶尔会**静默丢包**(没有断开、也没有错误)✅
所以每条请求都挂了超时,到点就回调失败 ✅ 不让调用方一直等 ✅

---

# 五、三种控制方式

| 选项 | 行为 |
|---|---|
| **自动(蓝牙优先)** | 蓝牙控制通道就绪 → 走蓝牙;否则走 WiFi |
| **只用蓝牙** | 蓝牙没连也**不回落**(用来验证蓝牙是否真的在工作) |
| **只用 WiFi** | 完全不用蓝牙(排查用) |

存在 `SharedPreferences` 的 `transport` 键 ✅

---

# 六、⚠️ 和「HID 走蓝牙」不是一回事

界面上有**两行**,别混淆:

```
🔵 蓝牙 HID  ·  📵 WiFi 不可达        ← 板子往手机**注入触摸**的链路
蓝牙控制通道:就绪(蓝牙,MTU=255) ✅   ← App **读写板子**的链路
控制方式:自动(蓝牙优先)  → 正在走蓝牙
```

| | HID 链路 | 控制链路 |
|---|---|---|
| 方向 | 板子 → 手机 | App ↔ 板子 |
| 谁负责 | **系统**(配对就行) | **我们自己做 GATT 客户端** |
| 靠什么固件 | 刷 `ble` 还是 `usb` | 一直用 BLE |
| 测什么 | 「蓝牙 HID」那行 | 「蓝牙控制通道」那行 |

**两条都通,才是完整的"蓝牙方式"** ✅

---

# 七、下一步(第四步)

**三种连接方式的端到端验证 + 文档**:
1. 蓝牙方式:控制 + HID 全走蓝牙(不用 WiFi)—— **已初步验证**
2. WiFi 方式:控制走 WiFi(现状)
3. USB 直插:注入走 USB,控制走 WiFi
4. 每种方式跑一个真实流程验证
