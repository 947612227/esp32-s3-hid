# 多连接方式 · 第二步:BLE 控制通道(固件)

## 要解决什么

**现状**:App 控制板子只能走 **WiFi HTTP** ✅
板子连不上公司网(要飞连认证)时 ⇒ **完全用不了** ✅
但**蓝牙明明连着**(HID 注入触摸正常工作)✅

**这一步**:在蓝牙上再开一个自定义 GATT 服务,
让 App **通过蓝牙**读状态 / 跑流程 / 传脚本 ✅

---

# 一、协议设计:为什么不直接转发 HTTP

考虑过"把 HTTP 请求塞进 BLE 分片",复用现有所有接口。

**放弃了** —— 板子的 HTTP handler 全是写给 `WebServer` 的
(`s_server.send(...)` / `s_server.arg(...)`),要抽象出一个"响应出口"
得改 `net_manager.cpp` 里四十多处,**风险太大**(WiFi 那套是好的)。

**改成:一条独立的文本命令协议**,能力对齐但只暴露必要的操作 ✅

```
请求(App → 板子):  PING | STATUS | LIST | GET <n> | RUN <n> | STOP
                  | LOG | LOGCLR | SAVE <n>\n<正文> | DEL <名字>
响应(板子 → App):  一行 OK / JSON / ERR <原因>
```

---

# 二、分片传输

BLE 单包很小(MTU 23 时载荷只有 20 字节),
而**流程脚本可能几 KB** ✅ 所以必须分片。

## 帧格式(双向一样)

```
[4 字节大端总长度][数据...]
```

**App → 板子**:第一个 write 的头 4 字节是总长度,后面是数据;
后续 write 全是数据 ✅ 板子累积到够长度才算收全 ✅

**板子 → App**:第一片 = 帧头 + 数据,后续片只发数据 ✅

分片大小按协商到的 MTU 算:

```cpp
uint16_t chunk = (_peerMtu > 3) ? _peerMtu - 3 : 20;   // 减 ATT 头
if (chunk < 20)   chunk = 20;
if (chunk > 240)  chunk = 240;                          // 保险,别太大
```

发每片之间 `delay(6)` —— 发太快蓝牙栈会丢 ✅

---

# 三、⚠️ 踩了个大坑:**广播包 31 字节超长**

加完控制服务后,板子启动就报:

```
E NimBLEAdvertisementData: Data length exceeded
```

**广播直接失败 ⇒ 手机搜不到板子** ✅

## 算一下就很清楚

BLE 广播包**上限 31 字节**:

| 内容 | 字节 |
|---|---|
| Flags | 3 |
| Appearance | 4 |
| HID 服务 UUID(1812,16 位) | 4 |
| 设备名 `ESP32S3 Touch HID` | **20** |
| **合计** | **31** ← 正好顶格! |

**再加任何东西都会溢出** ✅

## 第一次尝试(错了)

把 UUID 从 128 位改成 16 位 —— 从 16 字节降到 2 字节 ✅
**但还是超** —— 因为**名字本身就占了 20 字节** ✅

## 最终做法:**控制 UUID 根本不放进广播**

```cpp
// ⚠️ 控制服务的 UUID **故意不放进广播**
//    改成:App 连上之后**做服务发现**时再查有没有 A5C1
adv->addServiceUUID(UUID_HID_SERVICE);   // 只留 HID,回到 31 字节
// adv->addServiceUUID(UUID_CTRL_SERVICE);   ← 不加!
```

**App 连上后做 `discoverServices()` 一样能认出"支持蓝牙控制"** ✅
而且不占广播空间 ✅

**实测修好:**
```
Data length exceeded 出现 0 次   ✅
控制通道已开 出现 1 次           ✅
```

---

# 四、UUID(16 位私有)

```
服务  A5C1
RX    A5C2   App 写(WRITE | WRITE_NR)
TX    A5C3   板子通知(NOTIFY)
```

> 这几个没在蓝牙 SIG 注册,是自用的私有值 ✅
> 完整形式是 `0000A5C1-0000-1000-8000-00805F9B34FB`(后缀是蓝牙基址)✅

---

# 五、代码结构:为什么拆两个文件

```
src/ble/hid_transport_ble.cpp     ← GATT 服务 + 分片收发(NimBLE 依赖,只在 ble 环境编)
src/common/ble_command.cpp        ← 命令处理(纯逻辑,全环境编)
```

**为什么拆**:`src/common/` 是**所有环境**都编的 ✅
命令逻辑放进去没问题,但 **NimBLE 依赖不行**(usb 环境没有 NimBLE)✅
所以:GATT 那边只管收包/分包,命令逻辑单独一层 ✅

---

# 六、给 BLE 暴露的入口(从 NetManager)

BLE 那边**不能**用 `s_server`(那是 HTTP 的),所以开了几个等价的:

```cpp
void   NetManager::requestRun(uint8_t index);   // 排队跑流程(真正执行在 loop)
String NetManager::statusJson();                // 和 /api/status 同一份
String NetManager::logsJson();                  // 和 /api/logs 同一份
void   NetManager::clearLogs();
String NetManager::scriptText(uint8_t index);   // 和 /api/script 同一份
void   NetManager::stopSchedulePublic();
```

顺带把 `/api/status` 的 JSON 构建抽成了 `buildStatusJson()` ——
**HTTP 和 BLE 共用同一份**,不抽的话两边迟早不一致 ✅

---

# 七、重要:命令处理放在 `loop()`,不放蓝牙回调

```cpp
void serviceControl() {          // 由 poll() → loop() 调
    if (!_rxReady) return;       // 回调里只置标志
    ...
    String resp = bleHandleCommand(req);
    sendControl(resp);
}
```

**为什么**:NimBLE 回调跑在**蓝牙任务**里 ✅
在里面执行脚本 / 写文件系统 / 碰网络都**不安全** ✅
所以回调只负责"收全了置个标志",真正处理回到主循环 ✅

---

# 八、实测(固件侧)

```
[ble] 控制通道已开:a5c10001-... (RX 写 / TX 通知)     ← 服务创建成功
[ble] Appearance = 0x03C5 (数字转换器/触摸屏)
(无 Data length exceeded)                              ← 广播正常 ✅
[boot] HID 就绪(传输层: BLE HID (NimBLE / HOGP))
[net] 热点已开: ESP32-Touch-78C8 / 12345678
[net] 访问地址: http://192.168.4.1/
```

板子状态:
```
fw: 1.2.0  MAC: 28:84:85:89:78:C8
流程: 6  heap: 94 KB  HID: 就绪
```

**三个编译环境(ble / usb / usb_touchtest)全部通过** ✅

---

# 九、下一步(第三步)

**App 端 BLE 传输层**:
1. 连 BLE、做服务发现、认 A5C1
2. 按帧分片发命令 / 收响应
3. 让用户能选「控制走蓝牙还是 WiFi」
4. 蓝牙没连/不支持时自动回落 WiFi
