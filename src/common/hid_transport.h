// ============================================================================
//  HidTransport —— 传输后端抽象接口
//
//  设计目的:手势引擎(点击/滑动/贝塞尔/双指)与"报文怎么发出去"解耦,
//  同一份手势代码同时服务于 USB HID(Adafruit TinyUSB)和 BLE HID(NimBLE)。
//
//  两个后端只需实现:
//    - begin()
//    - ready()           端点/连接是否可用
//    - sendTouch()       发一帧触摸报文
//    - sendKeyboard() / sendConsumer() / sendMouse()
// ============================================================================
#pragma once

#include <stdint.h>

// 触摸槽位数与逻辑坐标范围(Report Map 里声明的必须与此一致)
static constexpr uint8_t TOUCH_SLOT_COUNT   = 10;
static constexpr uint8_t TOUCH_MAX_CONTACTS = 10;
static constexpr int32_t TOUCH_LOGICAL_MAX  = 32767;

// Report ID 分配(USB 与 BLE 共用同一套)
static constexpr uint8_t RID_TOUCHSCREEN = 1;
static constexpr uint8_t RID_KEYBOARD    = 2;
static constexpr uint8_t RID_CONSUMER    = 3;
static constexpr uint8_t RID_MOUSE       = 4;
static constexpr uint8_t RID_FEATURE_MAXCONTACTS = 5;
static constexpr uint8_t RID_FEATURE_DEVMODE     = 6;

// 键盘报文(BLE 与 USB 都是 8 字节)
struct KeyboardReport {
  uint8_t modifiers;
  uint8_t reserved;
  uint8_t keys[6];
};
static_assert(sizeof(KeyboardReport) == 8, "KeyboardReport 必须为 8 字节");

// Consumer Control 报文
struct ConsumerReport {
  uint16_t usage;
};
static_assert(sizeof(ConsumerReport) == 2, "ConsumerReport 必须为 2 字节");

// 鼠标报文
struct MouseReport {
  uint8_t buttons;
  int8_t  dx;
  int8_t  dy;
  int8_t  wheel;
};
static_assert(sizeof(MouseReport) == 4, "MouseReport 必须为 4 字节");

// ---------------------------------------------------------------------------
class HidTransport {
public:
  /** 主循环里定期调用,用于自愈等维护动作(默认什么都不做) */
  virtual void poll() {}

  /** 强制重置连接(断开对端并重新广播)。默认什么都不做 */
  virtual void resetLink() {}

  /** 连接诊断信息(JSON),给 /api/ble?op=info 用 */
  virtual String linkInfo() { return String("{}"); }

  virtual ~HidTransport() {}

  // 初始化 USB 或 BLE 协议栈
  virtual bool begin() = 0;

  // 是否可以发送(USB:端点就绪;BLE:已连接且 CCCD 已订阅)
  virtual bool ready() = 0;

  // 上一次发送是否已完成(用于流控)。
  // BLE 一个连接间隔(7.5~15ms)只能承载 1~2 个通知,发太快会整批丢弃;
  // USB 侧等价于端点 ready。手势引擎在连发报文之间会等这个。
  virtual bool readyToSend() = 0;

  // 发一帧触摸报文。tipMask/inRangeMask 是槽位位图,
  // xs/ys 是已经归一化到 0..TOUCH_LOGICAL_MAX 的逻辑坐标数组(长度 TOUCH_SLOT_COUNT)
  virtual bool sendTouch(uint8_t tipMask, uint8_t inRangeMask,
                         const uint8_t *contactIds, const uint16_t *xs,
                         const uint16_t *ys) = 0;

  virtual bool sendKeyboard(const KeyboardReport &rpt) = 0;
  virtual bool sendConsumer(const ConsumerReport &rpt) = 0;
  virtual bool sendMouse(const MouseReport &rpt)    = 0;

  // 传输层描述(用于日志)
  virtual const char *name() const = 0;
};

// 工厂:USB 与 BLE 各自实现,由构建环境决定链接哪一个
HidTransport &hidTransport();
