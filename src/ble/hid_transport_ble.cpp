// ============================================================================
//  BLE HID 传输后端(NimBLE,HID over GATT / HOGP)
//
//  为什么可行:Android 的 HOGP 主机栈收到 Report 通知后会写入 /dev/uhid,
//  之后走的是与 USB 完全相同的 hid-core / hid-input / hid-multitouch 路径。
//  所以 Report Map 原样复用,识别规则也完全一致(必须有 0x0D/0x04 + 0x51)。
//
//  BLE 侧特有的坑(本文件已处理):
//    1. Report 特征值必须带 CCCD(NOTIFY),且**必须等客户端订阅成功后再发**,
//       否则最早几个报文会静默丢失(表现为"第一次滑动没反应")。
//       判断订阅状态:onSubscribe 回调里的 subValue > 0。
//    2. 每个 Input Report 都要声明成独立的 Report 特征值,并把 Report Reference
//       Descriptor 填对(Report ID + Report Type),否则 Android 找不到对应关系。
//    3. HID Information 的 bcdHID 建议 >= 0x0111(1.11),否则部分 ROM 直接忽略。
//    4. Report Map 只允许有一个,且必须完整包含所有 Report ID 的定义。
//    5. 连接间隔尽量取小(7.5~15ms),否则滑动不跟手。
// ============================================================================
#include <Arduino.h>
#include <NimBLEDevice.h>
#include "../common/ble_command.h"

#include "../common/hid_report_map.h"
#include "../common/log_buffer.h"
#include "../common/hid_transport.h"

namespace {

// ---- HID over GATT 标准 UUID ----
#define UUID_HID_SERVICE   "1812"
#define UUID_HID_INFO      "2A4A"
#define UUID_REPORT_MAP    "2A4B"
#define UUID_HID_CONTROL   "2A4C"
#define UUID_REPORT        "2A4D"
#define UUID_PROTOCOL_MODE "2A4E"
#define UUID_PNP_ID        "2A50"
#define UUID_REPORT_REF    "2908"

// HID Information: bcdHID=0x0111, bCountryCode=0, Flags=0x02(NormallyConnectable)
const uint8_t HID_INFO[4] = {0x11, 0x01, 0x00, 0x02};
// PnP ID: sig=1(Bluetooth SIG), vid=0x303A(Espressif), pid, version
const uint8_t PNP_ID[7] = {0x01, 0x02, 0x0A, 0x00, 0x04, 0x40, 0x01};

const char *DEVICE_NAME = "ESP32S3 Touch HID";

// ---- 自定义「控制通道」UUID ----
//  HID 服务是标准 HOGP,只能注入触摸;
//  控制(App 读状态/跑流程/传脚本)以前必须走 WiFi ——
//  板子连不上公司网时就完全用不了。
//  这里再开一个自己的服务专门传数据,让蓝牙也能控制板子。
//
// ⚠️⚠️ **必须用 16 位短 UUID,不能用 128 位的** ——
//      BLE 广播包只有 **31 字节**。原来用 128 位 UUID(16 字节),
//      加上 HID 服务 + 设备名就溢出了,实测报:
//          E NimBLEAdvertisementData: Data length exceeded
//      广播直接失败 → **手机搜不到板子**。
//      16 位 UUID 只占 2 字节,绰绰有余。
//
//      这几个值没在蓝牙 SIG 注册,是自用的私有 UUID(完整形式是
//      0000XXXX-0000-1000-8000-00805F9B34FB,那个后缀就是蓝牙基址)。
#define UUID_CTRL_SERVICE "A5C1"
#define UUID_CTRL_RX      "A5C2"   // App 写
#define UUID_CTRL_TX      "A5C3"   // 板子通知

// 分片帧头:**每片都带** —— 4 字节总长度 + 4 字节本片偏移
//
// ⚠️⚠️ 一开始只在**第一片**带长度,后面全是裸数据。
//     结果:任何一片丢了(App 侧收通知有丢包),后面所有数据都被
//     当成帧头来解析,读出一个天量长度 → ByteArrayOutputStream 直接
//     OutOfMemoryError 把 App 崩掉(实测)。
//     每片都带 [总长][偏移] 之后:丢片能被**立刻发现**,
//     错位也不会蔓延,而且收到的片能按偏移直接落位。
#define CTRL_HDR 8
// 没协商到大 MTU 时的保守分片(默认 MTU 23 → 载荷 20)
#define CTRL_CHUNK_MIN 20
#define CTRL_CHUNK_MAX 240

class BleHidTransport : public HidTransport {
public:
  BleHidTransport() : _serverCb(this), _charCb(this) {}

  bool begin() override {
    // 控制通道收包缓冲的锁:onWrite 跑在 NimBLE 任务里,serviceControl 跑在 loop 任务里
    _rxMtx = xSemaphoreCreateMutex();
    if (_rxMtx == nullptr) return false;
    NimBLEDevice::init(DEVICE_NAME);
    // 默认用 Just Works 配对;如需固定 PIN,取消下面注释
    // NimBLEDevice::setSecurityPasskey(123456);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
    NimBLEDevice::setSecurityAuth(true, false, true);   // bond, no MITM, SC

    _server = NimBLEDevice::createServer();
    _server->setCallbacks(&_serverCb);

    NimBLEService *hid = _server->createService(UUID_HID_SERVICE);
    if (hid == nullptr) return false;

    // ---- HID Information ----
    NimBLECharacteristic *info =
        hid->createCharacteristic(UUID_HID_INFO, NIMBLE_PROPERTY::READ);
    info->setValue(HID_INFO, sizeof(HID_INFO));

    // ---- Report Map(整个复合 Map 只此一份)----
    NimBLECharacteristic *map =
        hid->createCharacteristic(UUID_REPORT_MAP, NIMBLE_PROPERTY::READ);
    map->setValue(HID_REPORT_MAP, HID_REPORT_MAP_LEN);

    // ---- HID Control Point(Suspend / Exit Suspend,只写)----
    NimBLECharacteristic *ctrl =
        hid->createCharacteristic(UUID_HID_CONTROL, NIMBLE_PROPERTY::WRITE_NR);
    ctrl->setCallbacks(&_charCb);

    // ---- Protocol Mode(固定 Report 模式 = 1)----
    NimBLECharacteristic *mode =
        hid->createCharacteristic(UUID_PROTOCOL_MODE, NIMBLE_PROPERTY::READ);
    const uint8_t reportMode = 1;
    mode->setValue(&reportMode, 1);

    // ---- 各 Input Report ----
    _chTouch    = makeInputReport(hid, RID_TOUCHSCREEN, sizeof(TouchReport));
    _chKeyboard = makeInputReport(hid, RID_KEYBOARD, sizeof(KeyboardReport));
    _chConsumer = makeInputReport(hid, RID_CONSUMER, sizeof(ConsumerReport));
    _chMouse    = makeInputReport(hid, RID_MOUSE, sizeof(MouseReport));
    if (_chTouch == nullptr || _chKeyboard == nullptr ||
        _chConsumer == nullptr || _chMouse == nullptr) {
      return false;
    }
    // 只有触摸屏特征值需要跟踪订阅状态
    _chTouch->setCallbacks(&_charCb);

    // ---- Feature Report:最大触点数(Report ID 5)----
    NimBLECharacteristic *feat =
        hid->createCharacteristic(UUID_REPORT, NIMBLE_PROPERTY::READ);
    NimBLEDescriptor *ref = new NimBLEDescriptor(UUID_REPORT_REF, 0, 2);
    const uint8_t maxRef[2] = {RID_FEATURE_MAXCONTACTS, 0x03};  // 0x03 = Feature
    ref->setValue(maxRef, sizeof(maxRef));
    feat->addDescriptor(ref);
    const uint8_t maxContacts = TOUCH_MAX_CONTACTS;
    feat->setValue(&maxContacts, 1);

    // ---- PnP ID ----
    NimBLECharacteristic *pnp =
        hid->createCharacteristic(UUID_PNP_ID, NIMBLE_PROPERTY::READ);
    pnp->setValue(PNP_ID, sizeof(PNP_ID));

    // ---- 自定义控制服务(蓝牙也能控制板子,不依赖 WiFi)----
    // ⚠️ 变量名别叫 ctrl —— begin() 里 HID 那边已经有一个 ctrl
    //    (HID Control Point 特征),重名会让 ctrl->createCharacteristic 编译不过
    NimBLEService *ctlSvc = _server->createService(UUID_CTRL_SERVICE);
    if (ctlSvc != nullptr) {
      NimBLECharacteristic *rx = ctlSvc->createCharacteristic(
          UUID_CTRL_RX, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
      NimBLECharacteristic *tx = ctlSvc->createCharacteristic(
          UUID_CTRL_TX, NIMBLE_PROPERTY::NOTIFY);
      rx->setCallbacks(&_ctrlCb);
      _ctrlTx = tx;
      Serial.println(F("[ble] 控制通道已开:a5c10001-... (RX 写 / TX 通知)"));
    }

    // ---- 广播 ----
    NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
    // ⚠️ Appearance 必须和设备的真实身份一致!
    //    之前为了"兼容性"设成 0x03C1(HID Keyboard),结果:虽然 HID 描述符里
    //    已经没有任何键盘集合,但三星读到广播里的 Appearance = Keyboard,
    //    就在聚焦输入框时弹「是否连入外接键盘」,而且关不掉。
    //    0x03C5 = HID Digitizer Tablet,这才是触摸屏该报的身份。
    adv->setAppearance(0x03C5);
    Serial.println(F("[ble] Appearance = 0x03C5 (数字转换器/触摸屏;0x03C1 是键盘)"));
    adv->addServiceUUID(UUID_HID_SERVICE);
    // ⚠️ 控制服务的 UUID **故意不放进广播** ——
    //    BLE 广播包只有 31 字节,而:
    //      flags(3) + appearance(4) + HID服务(4) + 设备名(20) = 31 —— 正好顶格!
    //    再加 4 字节的服务 UUID 就溢出,实测报:
    //      E NimBLEAdvertisementData: Data length exceeded
    //    广播直接失败 ⇒ **手机搜不到板子**。
    //
    //    改成:App 连上之后**做服务发现**时再查有没有 A5C1 ——
    //    一样能认出"这块板子支持蓝牙控制",而且不占广播空间。
    adv->setName(DEVICE_NAME);
    adv->enableScanResponse(true);
    adv->start();

    _ok = true;
    return true;
  }

  // 已连接 + CCCD 订阅完成,才可以发数据
  bool ready() override {
    bool r = _ok && peers() > 0 && _subCount > 0;
    if (r) _lastOkMs = millis();
    // 状态变化时打一次日志,便于排查"命令执行了但设备没反应"
    static int last = -1;
    int now = (peers() > 0 ? 1 : 0) | (_subCount > 0 ? 2 : 0);
    if (now != last) {
      Serial.printf("[ble] ready=%d (peers=%d subs=%d)\n",
                    (int)r, peers(), (int)_subCount);
      last = now;
    }
    return r;
  }

  /**
   * 连接诊断:把"到底能连几个、已经连了几个、绑定了几台"直接报出来。
   *
   * NimBLE 默认上限:MAX_CONNECTIONS=3、MAX_BONDS=3。
   * 但这套固件只维护**一份**连接/订阅状态,逻辑上同时只服务一台手机。
   */
  String linkInfo() override {
    String j = "{";
    int np = peers();
    j += "\"connected\":" + String(np > 0 ? "true" : "false");
    j += ",\"subscribed\":" + String(_subCount > 0 ? "true" : "false");
    j += ",\"peers\":" + String(np);
    j += ",\"subs\":" + String((int)_subCount);
    j += ",\"maxConnections\":" + String(CONFIG_BT_NIMBLE_MAX_CONNECTIONS);
    j += ",\"maxBonds\":" + String(CONFIG_BT_NIMBLE_MAX_BONDS);
    j += ",\"bonds\":" + String((int)NimBLEDevice::getNumBonds());
    bool adv = false;
    if (NimBLEDevice::getAdvertising()) adv = NimBLEDevice::getAdvertising()->isAdvertising();
    j += ",\"advertising\":" + String(adv ? "true" : "false");
    // 发送统计:缓冲满后重试成功的次数 / 重试用完仍失败的次数
    j += ",\"notifyRetries\":" + String((unsigned)_notifyRetries);
    j += ",\"notifyFails\":" + String((unsigned)_notifyFails);
    // 已绑定的地址列表
    j += ",\"bondList\":[";
    for (int i = 0; i < (int)NimBLEDevice::getNumBonds(); i++) {
      if (i) j += ",";
      j += "\"" + String(NimBLEDevice::getBondedAddress(i).toString().c_str()) + "\"";
    }
    j += "]}";
    return j;
  }

  bool sendTouch(uint8_t tipMask, uint8_t inRangeMask, const uint8_t *contactIds,
                 const uint16_t *xs, const uint16_t *ys) override {
    if (!ready()) return false;
    TouchReport rpt;
    for (uint8_t i = 0; i < TOUCH_SLOT_COUNT; i++) {
      TouchSlot &s = rpt.slot[i];
      // 描述符里 Tip/InRange 各占 1 位、共用同一个字节
      s.flags = 0;
      if (tipMask & (1u << i))     s.flags |= TOUCH_FLAG_TIP;
      if (inRangeMask & (1u << i)) s.flags |= TOUCH_FLAG_RANGE;
      s.contactId = contactIds[i];
      s.x         = xs[i];
      s.y         = ys[i];
    }
    return notify(_chTouch, RID_TOUCHSCREEN, &rpt, sizeof(rpt));
  }

  // 上一次 notify 是否成功(流控用)
  bool readyToSend() override { return _lastNotifyOk; }

  /** 由主循环调用,做自愈检查 */
  /**
   * 处理控制通道收到的命令,并把响应分片发回去。
   *
   * ⚠️ 在 **loop()** 里跑(不是 NimBLE 回调里):
   *    处理命令会碰 Scripts / 文件系统 / 网络,放在蓝牙回调的任务里不安全。
   */
  void serviceControl() {
    if (!_rxReady || _inControl) return;
    // 防重入:命令处理里若间接走到 poll()(如等待钩子),不能再嵌套处理下一条
    struct Guard { bool &b; Guard(bool &x) : b(x) { b = true; } ~Guard() { b = false; } } g(_inControl);
    // 从"已收全"队列里取走一条请求(和 onWrite 互斥);每轮只处理一条,剩下的下一轮
    String req;
    uint16_t conn = BLE_HS_CONN_HANDLE_NONE;
    xSemaphoreTake(_rxMtx, portMAX_DELAY);
    if (_rxDoneN > 0) {
      req = _rxDone[_rxDoneHead];
      _rxDone[_rxDoneHead] = "";
      conn = _rxDoneConn[_rxDoneHead];
      _rxDoneHead = (_rxDoneHead + 1) % RX_Q_MAX;
      _rxDoneN--;
    }
    _rxReady = _rxDoneN > 0;
    xSemaphoreGive(_rxMtx);
    if (req.isEmpty()) return;
    _ctrlConn = conn;

    // ⚠️ 打耗时 —— 蓝牙一次往返 2~3 秒,到底是"命令处理慢"还是"链路慢",
    //    不打点只能猜。t0 = 收到命令,t1 = 处理完,t2 = 全部发完。
    uint32_t t0 = millis();
    LOGF("[ble] 控制命令:%s", req.substring(0, 60).c_str());
    String resp = bleHandleCommand(req);
    uint32_t t1 = millis();
    sendControl(resp);
    uint32_t t2 = millis();
    LOGF("[ble] 耗时:处理 %ums,发送 %ums,合计 %ums(响应 %u 字节)",
         (unsigned)(t1 - t0), (unsigned)(t2 - t1), (unsigned)(t2 - t0),
         (unsigned)resp.length());
  }

  /** 把响应按帧发回去(4 字节长度 + 分片 notify) */
  void sendControl(const String &resp) {
    if (_ctrlTx == nullptr) return;
    uint32_t total = resp.length();
    // 分片大小:MTU - 3(ATT 头),夹在保守区间里
    // ⚠️ 先实时问一遍 MTU,别只信回调 ——
    //    onMTUChange 不一定被调用(NimBLE 版本/时序差异),
    //    结果就是一直按默认 23 分片,707 字节要发 37 片(实测踩过)。
    if (_server && _server->getPeerDevices().size() > 0) {
      // 优先按"发请求的那台"的 MTU 分片(多连接时各台 MTU 可能不同)
      uint16_t h = (_ctrlConn != BLE_HS_CONN_HANDLE_NONE)
                       ? _ctrlConn
                       : _server->getPeerInfo((uint8_t)0).getConnHandle();
      uint16_t m = _server->getPeerMTU(h);
      if (m > 3 && m != _peerMtu) {
        Serial.printf("[ble] MTU 实时查到 %u(缓存是 %u)\n", (unsigned)m, (unsigned)_peerMtu);
        _peerMtu = m;
      }
    }
    uint16_t chunk = (uint16_t)(_peerMtu > 3 ? _peerMtu - 3 : CTRL_CHUNK_MIN);
    if (chunk < CTRL_CHUNK_MIN) chunk = CTRL_CHUNK_MIN;
    if (chunk > CTRL_CHUNK_MAX) chunk = CTRL_CHUNK_MAX;

    uint32_t sent = 0;
    while (sent < total) {
      String part = resp.substring(sent, sent + chunk);
      // 每片都封上 [总长][偏移] —— 见 CTRL_HDR 的注释,这是防崩的关键
      String frame;
      frame.reserve(CTRL_HDR + part.length());
      frame += (char)((total >> 24) & 0xFF);
      frame += (char)((total >> 16) & 0xFF);
      frame += (char)((total >> 8) & 0xFF);
      frame += (char)(total & 0xFF);
      frame += (char)((sent >> 24) & 0xFF);
      frame += (char)((sent >> 16) & 0xFF);
      frame += (char)((sent >> 8) & 0xFF);
      frame += (char)(sent & 0xFF);
      frame += part;
      // ⚠️ 必须用"带数据"的 notify(data, len, conn):它当场把这一片拷进发送缓冲。
      //    无参的 notify() 走 ble_gatts_chr_updated(),到真正发出时才去读特征值,
      //    下一片的 setValue 会把还没发出的上一片覆盖掉 ⇒ 丢片(以前只能靠片间 delay(30) 硬扛)。
      //    发送缓冲满时返回失败,这里短暂退避重试。
      if (!notifyTo(_ctrlTx, (const uint8_t *)frame.c_str(), frame.length(),
                    _ctrlConn)) {
        Serial.printf("[ble] 控制响应第 %u 字节处发送失败,放弃本帧\n", (unsigned)sent);
        break;
      }
      sent += part.length();
      if (part.length() == 0) break;      // 兜底,别死循环
    }
    Serial.printf("[ble] 控制响应 %u 字节,分 %u 片(MTU %u,片头 8 字节)\n",
                  (unsigned)total, (unsigned)((total + chunk - 1) / chunk),
                  (unsigned)_peerMtu);
  }

  void poll() override { tick(); }

  /**
   * 强制重置连接:断开对端 + 重新广播。
   * 用于手机侧 HOGP 卡住(蓝牙显示已连接但内核没有设备)时人工修复。
   */
  void resetLink() override {
    Serial.printf("[ble] 强制重置连接(断开全部 %d 台)\n", peers());
    // 多连接:要把**每一台**都断开,不能只断 peer0
    for (int i = peers() - 1; i >= 0; i--) {
      if (_server) _server->disconnect(_server->getPeerInfo((uint8_t)i).getConnHandle());
      delay(150);
    }
    _connected = false;
    clearSubs();
    _lastNotifyOk = false;
    _lastOkMs = millis();
    NimBLEDevice::stopAdvertising();
    delay(200);
    NimBLEDevice::startAdvertising();
    Serial.println(F("[ble] 已重新开始广播"));
  }

  bool sendKeyboard(const KeyboardReport &rpt) override {
    if (!ready()) return false;
    return notify(_chKeyboard, RID_KEYBOARD, &rpt, sizeof(rpt));
  }
  bool sendConsumer(const ConsumerReport &rpt) override {
    if (!ready()) return false;
    return notify(_chConsumer, RID_CONSUMER, &rpt, sizeof(rpt));
  }
  bool sendMouse(const MouseReport &rpt) override {
    if (!ready()) return false;
    return notify(_chMouse, RID_MOUSE, &rpt, sizeof(rpt));
  }

  const char *name() const override { return "BLE HID (NimBLE / HOGP)"; }

  // ---- 供回调访问 ----
  /** 当前连接数(BLE 协议栈是权威来源,别自己维护布尔) */
  int peers() const { return _server ? (int)_server->getConnectedCount() : 0; }

  /**
   * 记录/取消某个连接对触摸 Report 的订阅。
   * 按连接句柄去重 —— 同一台手机重复上报订阅事件不会被算成两台。
   */
  void setSub(uint16_t conn, bool on) {
    portENTER_CRITICAL(&_subMux);
    int idx = -1;
    for (int i = 0; i < _subCount; i++) if (_subHandles[i] == conn) idx = i;
    if (on && idx < 0 && _subCount < SUB_MAX) _subHandles[_subCount++] = conn;
    if (!on && idx >= 0) _subHandles[idx] = _subHandles[--_subCount];
    portEXIT_CRITICAL(&_subMux);
    _connected = peers() > 0;
    if (on) _lastOkMs = millis();
  }

  /** 某台断开:去掉它的订阅;一台都不剩时清空全部状态 */
  void onPeerGone(uint16_t conn) {
    setSub(conn, false);
    _connected = peers() > 0;
    if (peers() == 0) clearSubs();
  }

  void clearSubs() {
    portENTER_CRITICAL(&_subMux);
    _subCount = 0;
    portEXIT_CRITICAL(&_subMux);
  }

private:
  // ------------------------------------------------------------------
  //  回调
  // ------------------------------------------------------------------
  class ServerCallbacks : public NimBLEServerCallbacks {
  public:
    explicit ServerCallbacks(BleHidTransport *owner) : _owner(owner) {}

    /**
     * MTU 协商完成 —— 记下来,回包时按它分片。
     *
     * ⚠️ 一开始只在 onSubscribe 里读 connInfo.getMTU(),结果拿到的是**默认 23**,
     *    707 字节的日志被切成 **37 片**(每片 20 字节),慢得离谱。
     *    安卓那边明明协商到了 255。
     *    这个回调才是 MTU 真正生效的时机。
     */
    void onMTUChange(uint16_t mtu, NimBLEConnInfo &info) override {
      _owner->_peerMtu = mtu;
      Serial.printf("[ble] MTU 协商为 %u(控制通道回包按这个分片)\n", (unsigned)mtu);
    }

    /**
     * 连接参数(含连接间隔)协商结果。
     *
     * ⚠️ 这个必须打出来 —— 蓝牙"一次往返 2~3 秒"很可能就是
     *    连接间隔(interval)太大导致的(比如 100ms+ 一帧,
     *    一次请求要好几帧)。不看这个数就只能瞎猜。
     */
    void onConnParamsUpdate(NimBLEConnInfo &info) override {
      Serial.printf("[ble] 连接参数:interval=%.2fms latency=%u timeout=%ums\n",
                    info.getConnInterval() * 1.25f,
                    (unsigned)info.getConnLatency(),
                    (unsigned)(info.getConnTimeout() * 10));
    }

    void onConnect(NimBLEServer *srv, NimBLEConnInfo &info) override {
      // 每台手机单独设置连接参数(7.5~15ms,滑动才跟手)
      srv->updateConnParams(info.getConnHandle(), 6, 12, 0, 400);
      _owner->_connected = true;
      _owner->_lastOkMs = millis();

      // ⚠️ 连上后**继续保持广播**。
      //    NimBLE 默认一连上就停广播 → 第二台手机根本搜不到板子,
      //    也就没法做"多台手机同步操作"。上限 CONFIG_BT_NIMBLE_MAX_CONNECTIONS(3)。
      if (_owner->peers() < CONFIG_BT_NIMBLE_MAX_CONNECTIONS) {
        if (!NimBLEDevice::getAdvertising()->isAdvertising()) {
          NimBLEDevice::startAdvertising();
        }
      }
      Serial.printf("[ble] 已连接(连接数=%d/%d),继续广播,等待 CCCD 订阅...\n",
                    _owner->peers(), (int)CONFIG_BT_NIMBLE_MAX_CONNECTIONS);
    }

    void onDisconnect(NimBLEServer *srv, NimBLEConnInfo &info,
                      int reason) override {
      (void)srv; (void)reason;
      _owner->onPeerGone(info.getConnHandle());
      Serial.printf("[ble] 一台断开(%s),剩余连接数=%d 订阅数=%d\n",
                    info.getAddress().toString().c_str(),
                    _owner->peers(), (int)_owner->_subCount);
      // 还有空位就继续广播,方便接入第二台/第三台手机(多机同步)
      if (_owner->peers() < CONFIG_BT_NIMBLE_MAX_CONNECTIONS
          && !NimBLEDevice::getAdvertising()->isAdvertising()) {
        NimBLEDevice::startAdvertising();
      }
    }

  private:
    BleHidTransport *_owner;
  };

  /**
   * 控制通道的接收回调 —— 处理 App 写进来的**分片**。
   *
   * 帧格式:[4 字节大端总长度][数据...]
   * 数据可能跨好几个 write(每个 write 最多 MTU-3 字节),所以这里累积。
   * 收全之后只置个标志,**真正的处理放到 loop()** ——
   * 回调跑在 NimBLE 的任务里,在里面执行脚本/写文件不安全。
   */
  class CtrlCallbacks : public NimBLECharacteristicCallbacks {
   public:
    explicit CtrlCallbacks(BleHidTransport *t) : _t(t) {}

    void onWrite(NimBLECharacteristic *ch, NimBLEConnInfo &info) override {
      NimBLEAttValue v = ch->getValue();
      const uint8_t *d = v.data();
      size_t n = v.length();
      if (n == 0) return;
      _t->_lastCtrlMs = millis();

      xSemaphoreTake(_t->_rxMtx, portMAX_DELAY);
      // 帧头:还没拿到长度时,前 4 字节就是长度
      if (_t->_rxWant == 0) {
        if (n < CTRL_HDR) { xSemaphoreGive(_t->_rxMtx); return; }
        uint32_t want = ((uint32_t)d[0] << 24) | ((uint32_t)d[1] << 16) |
                        ((uint32_t)d[2] << 8) | (uint32_t)d[3];
        d += CTRL_HDR;
        n -= CTRL_HDR;
        if (want == 0 || want > CTRL_RX_MAX) {
          Serial.printf("[ble] 控制帧长度非法(%u),丢弃\n", (unsigned)want);
          xSemaphoreGive(_t->_rxMtx);
          return;
        }
        _t->_rxWant = want;
        _t->_rxConn = info.getConnHandle();
        _t->_rxBuf = "";
        _t->_rxBuf.reserve(want + 8);
      }
      for (size_t i = 0; i < n; i++) _t->_rxBuf += (char)d[i];
      if ((uint32_t)_t->_rxBuf.length() >= _t->_rxWant) {
        // 收全了:移交给"已收全"缓冲,立刻复位收包状态,下一条请求可以马上开始收。
        // (以前 _rxWant 要等 loop 处理完才复位,期间到的新请求会被当成上一条的续片 ⇒ 乱帧)
        if (_t->_rxDoneN >= RX_Q_MAX) {
          Serial.println(F("[ble] 待处理控制请求已满,新请求被丢弃"));
        } else {
          int tail = (_t->_rxDoneHead + _t->_rxDoneN) % RX_Q_MAX;
          _t->_rxDone[tail] = _t->_rxBuf;
          _t->_rxDoneConn[tail] = _t->_rxConn;
          _t->_rxDoneN++;
          _t->_rxReady = true;
        }
        _t->_rxBuf = "";
        _t->_rxWant = 0;
      }
      xSemaphoreGive(_t->_rxMtx);
    }

    void onSubscribe(NimBLECharacteristic *ch, NimBLEConnInfo &info,
                     uint16_t subValue) override {
      // 记下 MTU,回包时按它分片
      _t->_peerMtu = info.getMTU();
      Serial.printf("[ble] 控制通道订阅 sub=%u,MYU=%u\n", (unsigned)subValue,
                    (unsigned)_t->_peerMtu);
    }

   private:
    BleHidTransport *_t;
  };

  class CharCallbacks : public NimBLECharacteristicCallbacks {
  public:
    explicit CharCallbacks(BleHidTransport *owner) : _owner(owner) {}

    void onSubscribe(NimBLECharacteristic *chr, NimBLEConnInfo &info,
                     uint16_t subValue) override {
      if (chr == nullptr) return;
      if (chr->getUUID() == NimBLEUUID(UUID_HID_CONTROL)) {
        auto val = chr->getValue();
        if (val.size() >= 1) {
          Serial.printf("[ble] HID Control Point: %s\n",
                        val.data()[0] == 0 ? "Suspend" : "Exit Suspend");
        }
        return;
      }
      // subValue 位 0 = Notify,位 1 = Indicate
      bool on = (subValue & 0x01) != 0;
      _owner->setSub(info.getConnHandle(), on);
      Serial.printf("[ble] Report %s(订阅数=%d,连接数=%d)\n",
                    on ? "已订阅 → 可以发数据" : "取消订阅",
                    _owner->_subCount, _owner->peers());
    }

  private:
    BleHidTransport *_owner;
  };

  // ------------------------------------------------------------------
  //  工具
  // ------------------------------------------------------------------
  NimBLECharacteristic *makeInputReport(NimBLEService *svc, uint8_t reportId,
                                        uint16_t len) {
    NimBLECharacteristic *ch = svc->createCharacteristic(
        UUID_REPORT, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    if (ch == nullptr) return nullptr;

    // Report Reference Descriptor: [Report ID, Report Type(1 = Input)]
    NimBLEDescriptor *ref = new NimBLEDescriptor(UUID_REPORT_REF, 0, 2);
    const uint8_t rr[2] = {reportId, 0x01};
    ref->setValue(rr, sizeof(rr));
    ch->addDescriptor(ref);

    // 预置全零值,长度与 Report Map 声明一致(含 1 字节 Report ID)
    uint8_t zeros[64] = {0};
    if (len > sizeof(zeros) - 1) len = sizeof(zeros) - 1;
    zeros[0] = reportId;
    ch->setValue(zeros, len + 1);
    return ch;
  }

  // ★ BLE 侧必须手工把 Report ID 作为报文的第 1 个字节发出去。
  //   HOGP 只传特征值,不带 Report ID 字段;主机(Android)靠报文首字节
  //   匹配 Report Map 里的 Report ID。少了这一字节,主机会因为
  //   "Report ID 未知"而**静默丢弃整包**(实测:设备属性全对、却收不到任何事件)。
  //   注意 USB 侧不需要这么做 —— Adafruit 的 sendReport() 内部会自己加前缀。
  bool notify(NimBLECharacteristic *ch, uint8_t reportId, const void *data,
              size_t len) {
    if (ch == nullptr) return false;
    uint8_t wire[1 + 64];
    if (len > sizeof(wire) - 1) return false;
    wire[0] = reportId;
    memcpy(wire + 1, data, len);
    ch->setValue(wire, len + 1);   // 只为了让 READ 读到最新值

    // 逐个发给"已订阅触摸 Report"的连接。
    // ⚠️ 带数据的 notify 不检查 CCCD,所以不能用 BLE_HS_CONN_HANDLE_NONE(会发给
    //    只连了控制通道、没订阅 HID 的 App 连接);只发给自己记录的订阅句柄。
    // ⚠️ 以前的无参 notify() 走 ble_gatts_chr_updated(),永远返回成功,
    //    而且连续两帧会互相覆盖(按下/抬起帧丢失 ⇒ 卡手指)。
    uint16_t hs[SUB_MAX];
    int n = 0;
    portENTER_CRITICAL(&_subMux);
    for (int i = 0; i < _subCount; i++) hs[n++] = _subHandles[i];
    portEXIT_CRITICAL(&_subMux);

    bool ok = n > 0;
    for (int i = 0; i < n; i++) {
      if (!notifyTo(ch, wire, len + 1, hs[i])) ok = false;
    }
    _lastNotifyOk = ok;
    if (ok) _lastOkMs = millis();
    else {
      Serial.printf("[ble] notify 失败 订阅数=%d 连接数=%d len=%u\n", n, peers(),
                    (unsigned)(len + 1));
    }
    return ok;
  }

  /**
   * 带数据发一条通知给指定连接,发送缓冲满(ENOMEM)时退避重试。
   * 返回 false = 重试用完仍失败(多半是链路已断)。
   */
  bool notifyTo(NimBLECharacteristic *ch, const uint8_t *data, size_t len,
                uint16_t conn) {
    if (ch == nullptr) return false;
    // 退避:1,2,4,8,16,16... ms,总计约 100ms
    uint32_t wait = 1;
    for (int attempt = 0; attempt < 9; attempt++) {
      if (ch->notify(data, len, conn)) {
        if (attempt > 0) _notifyRetries += attempt;
        return true;
      }
      // 连接已经不在了就别白等(断开回调可能还没来得及清掉句柄)
      if (conn != BLE_HS_CONN_HANDLE_NONE && ble_gap_conn_find(conn, nullptr) != 0) break;
      delay(wait);
      if (wait < 16) wait *= 2;
    }
    _notifyFails++;
    return false;
  }

  NimBLEServer         *_server = nullptr;
  // ---- 控制通道 ----
  NimBLECharacteristic *_ctrlTx = nullptr;   // 板子 → App(notify)
  CtrlCallbacks         _ctrlCb{this};
  SemaphoreHandle_t     _rxMtx = nullptr;    // 保护下面的收包状态
  String                _rxBuf;              // 正在拼的帧
  uint32_t              _rxWant = 0;         // 本帧总长度(0 = 还没收到帧头)
  uint16_t              _rxConn = BLE_HS_CONN_HANDLE_NONE;      // 正在拼的帧来自哪个连接
  // 已收全、等 loop 处理的请求(小环形队列;多台手机/重试时可能连着到好几条)
  static constexpr int  RX_Q_MAX = 4;
  String                _rxDone[RX_Q_MAX];
  uint16_t              _rxDoneConn[RX_Q_MAX] = {};  // 各条来自哪个连接
  int                   _rxDoneHead = 0;
  int                   _rxDoneN = 0;
  volatile bool         _rxReady = false;    // 队列非空
  uint16_t              _ctrlConn = BLE_HS_CONN_HANDLE_NONE;    // 当前在回复的连接
  volatile uint32_t     _lastCtrlMs = 0;     // 最近一次收到控制通道数据
  bool                  _inControl = false;  // 正在处理控制命令(防重入)
  static constexpr uint32_t CTRL_RX_MAX = 64 * 1024;  // 单条请求上限(防坏帧头撑爆内存)
  uint16_t              _peerMtu = 23;       // 协商到的 MTU
  NimBLECharacteristic *_chTouch = nullptr;
  NimBLECharacteristic *_chKeyboard = nullptr;
  NimBLECharacteristic *_chConsumer = nullptr;
  NimBLECharacteristic *_chMouse = nullptr;
  ServerCallbacks       _serverCb;
  CharCallbacks         _charCb;
  // ⚠️ 多连接:不能用"一个布尔"表示连接/订阅状态。
  //    实测踩过:手机连着、Mac 又连一台,Mac 一断开就把状态清成"未连接",
  //    虽然手机还在(peers=1)→ 手机的触摸注入直接失效。
  //    所以按"连接数 / 订阅数"计数,只要还有一台可用就算就绪。
  static constexpr int  SUB_MAX = CONFIG_BT_NIMBLE_MAX_CONNECTIONS;
  portMUX_TYPE          _subMux = portMUX_INITIALIZER_UNLOCKED;
  uint16_t              _subHandles[SUB_MAX] = {};  // 已订阅触摸 Report 的连接句柄
  volatile int          _subCount = 0;     // 已订阅 Report 的客户端数(= _subHandles 有效个数)
  volatile uint32_t     _notifyRetries = 0;  // 发送缓冲满、重试成功的次数(统计用)
  volatile uint32_t     _notifyFails = 0;    // 重试用完仍失败的次数(统计用)
  volatile bool         _connected = false;  // 派生值,仅用于展示/看门狗
  volatile bool         _lastNotifyOk = true;
  volatile uint32_t     _lastOkMs = 0;      // 最近一次成功(连接/订阅/发送)
  bool                  _ok = false;
public:
  /**
   * 自愈看门狗。
   *
   * 反复遇到的现象:板子"以为还连着"(_connected=true),但手机侧其实已经断了
   * (HID connection state=0),于是板子**不再广播** → 手机永远连不上,
   * 只能重启板子。这个状态在反复配对/解绑时特别容易出现。
   *
   * 对策:如果自称已连接,但连续 60 秒既没有 HID 订阅、也没有控制通道流量,
   * 就判定为"假连接",主动断开并重新广播。
   *
   * ⚠️ "HID 已订阅"本身就算健康:手机放着不动时不会有任何触摸发送,
   *    以前只看"最近成功发送"⇒ 空闲 60 秒就把好好的连接踢掉。
   *    链路真的断了,协议栈的监督超时(4 秒)会让连接数自己掉下来。
   */
  void tick() {
    if (!_ok) return;
    // 控制通道每轮都处理 —— 以前放在 5 秒节流里面,
    // App 每条请求平均要白等 2.5 秒才被处理(实测"一次往返 2~3 秒"的根因)
    serviceControl();
    _tickBody();
  }

private:
  uint32_t              _lastTickMs = 0;
  void _tickBody() {
    uint32_t now = millis();
    if (now - _lastTickMs < 5000) return;      // 看门狗 5 秒检查一次
    _lastTickMs = now;

    // 还有空位却没在广播(如断开回调里重启广播失败),补一次
    if (peers() < CONFIG_BT_NIMBLE_MAX_CONNECTIONS && NimBLEDevice::getAdvertising() &&
        !NimBLEDevice::getAdvertising()->isAdvertising()) {
      NimBLEDevice::startAdvertising();
      Serial.println(F("[ble] 看门狗:广播掉了,已重新开始"));
    }
    if (peers() == 0) return;

    if (_subCount > 0) { _lastOkMs = now; return; }        // HID 已订阅 = 健康
    if (now - _lastCtrlMs < 60000UL) return;               // 控制通道最近有流量 = 健康
    if (_lastOkMs == 0) _lastOkMs = now;
    if (now - _lastOkMs > 60000UL) {
      Serial.printf("[ble] 看门狗:60 秒无订阅也无控制流量,判定为假连接,断开全部 %d 台\n",
                    peers());
      for (int i = peers() - 1; i >= 0; i--) {
        if (_server) _server->disconnect(_server->getPeerInfo((uint8_t)i).getConnHandle());
        delay(150);
      }
      _connected = false;
      clearSubs();
      delay(200);
      NimBLEDevice::startAdvertising();
      _lastOkMs = millis();
    }
  }
};

BleHidTransport g_ble;

}  // namespace

HidTransport &hidTransport() { return g_ble; }
