// ============================================================================
//  USB HID 传输后端(Adafruit TinyUSB)
//
//  说明:Adafruit TinyUSB 不支持"全局设一个 Report Map",必须通过
//  Adafruit_USBD_HID 接口类注册。它内部维护 tud_hid_descriptor_report_cb,
//  并负责上报 Report Map 与报文分发。
// ============================================================================
#include <Adafruit_TinyUSB.h>

#include "../common/hid_report_map.h"
#include "../common/hid_transport.h"

namespace {

class UsbHidTransport : public HidTransport {
public:
  UsbHidTransport()
      : _hid(HID_REPORT_MAP, HID_REPORT_MAP_LEN, HID_ITF_PROTOCOL_NONE, 4,
             false) {}

  bool begin() override {
#ifndef HID_TOUCH_VID
#define HID_TOUCH_VID 0x303A
#endif
#ifndef HID_TOUCH_PID
#define HID_TOUCH_PID 0x4004
#endif
    TinyUSBDevice.setID(HID_TOUCH_VID, HID_TOUCH_PID);
    TinyUSBDevice.setManufacturerDescriptor("ESP32-S3");
    TinyUSBDevice.setProductDescriptor("HID Touch Injector");
    TinyUSBDevice.setDeviceVersion(0x0100);

    // Feature Report 查询(最大触点数 / 设备模式)
    _hid.setReportCallback(onGetReport, nullptr);

    // ★ 顺序必须照 Adafruit 官方示例来:先 TinyUSBDevice.begin(),再 _hid.begin()。
    //   反过来写的话,设备一旦被主机枚举,GET HID REPORT DESCRIPTOR 会去查
    //   Adafruit 内部的 _hid_instances[itf],而该接口尚未注册 → 空指针
    //   → StoreProhibited panic + 无限重启(实测踩过)。
    if (!TinyUSBDevice.isInitialized()) {
      if (!TinyUSBDevice.begin(0)) return false;
    }
    if (!_hid.begin()) return false;

    // 若主机在我们注册接口之前就已经枚举过(比如板子刚复位时手机还挂着),
    // 需要 detach/attach 强制重新枚举一次,否则 Report Map 拿不到。
    if (TinyUSBDevice.mounted()) {
      TinyUSBDevice.detach();
      delay(10);
      TinyUSBDevice.attach();
    }

    _ok = true;
    // 等主机枚举完成(最多 3 秒),期间打印状态便于排查
    uint32_t t0 = millis();
    while (!TinyUSBDevice.mounted() && millis() - t0 < 3000) {
      delay(50);
    }
    return true;
  }

  bool ready() override {
    return _ok && _hid.isValid() && TinyUSBDevice.mounted();
  }

  // USB 端点是否可写(流控用)
  bool readyToSend() override { return _ok && _hid.isValid() && _hid.ready(); }

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
    return send(RID_TOUCHSCREEN, &rpt, sizeof(rpt));
  }

  bool sendKeyboard(const KeyboardReport &rpt) override {
    return send(RID_KEYBOARD, &rpt, sizeof(rpt));
  }
  bool sendConsumer(const ConsumerReport &rpt) override {
    return send(RID_CONSUMER, &rpt, sizeof(rpt));
  }
  bool sendMouse(const MouseReport &rpt) override {
    return send(RID_MOUSE, &rpt, sizeof(rpt));
  }

  const char *name() const override { return "USB HID (TinyUSB)"; }

private:
  static uint16_t onGetReport(uint8_t reportId, hid_report_type_t type,
                              uint8_t *buffer, uint16_t bufsize) {
    (void)type;
    if (buffer == nullptr || bufsize == 0) return 0;
    if (reportId == RID_FEATURE_MAXCONTACTS) {
      buffer[0] = TOUCH_MAX_CONTACTS;
      return 1;
    }
    if (reportId == RID_FEATURE_DEVMODE) {
      buffer[0] = 1;   // 1 = 触摸屏模式
      return 1;
    }
    return 0;
  }

  bool send(uint8_t rid, const void *data, size_t len) {
    // 两道闸:
    //  1. 设备必须已被主机枚举(mounted)
    //  2. HID 接口实例必须注册成功(isValid),否则 _instance 是 0xff,
    //     底层会拿它去索引内部数组 → 越界/空指针
    if (!TinyUSBDevice.mounted() || !_hid.isValid()) {
      return false;
    }
    uint32_t guard = millis();
    while (!_hid.ready()) {
      if (millis() - guard > 50) return false;
      delay(1);
    }
    return _hid.sendReport(rid, data, (uint8_t)len);
  }

  Adafruit_USBD_HID _hid;
  bool _ok = false;
};

UsbHidTransport g_usb;

}  // namespace

HidTransport &hidTransport() { return g_usb; }
