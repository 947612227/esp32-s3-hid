// ============================================================================
//  HID Report Map —— 触摸屏(digitizer) + 键盘 + Consumer + 鼠标 复合设备
//
//  这个描述符是 USB 和 BLE 共用的。Android 的 HOGP 主机栈最终把报文喂给
//  /dev/uhid,走的是与 USB 完全相同的 hid-core / hid-multitouch 逻辑,
//  所以两边的描述符规则一模一样。
//
//  为什么必须是这个形状(而不是"绝对坐标鼠标"):
//
//  1. 顶层集合必须是 Usage Page 0x0D (Digitizer) / Usage 0x04 (Touch Screen)。
//     如果只写 0x01/0x02 (Generic Desktop / Mouse) 再配绝对坐标,内核只会给出
//     ABS_X/ABS_Y + BTN_LEFT;Android 的 EventHub 既不给 CURSOR(要 REL_X/REL_Y)
//     也不给 TOUCH(要 BTN_TOUCH),结果是 classes == 0 —— 设备被整个丢弃。
//
//  2. Input 项里必须出现 Contact Identifier (0x0D/0x51)。Linux 的
//     hid_scan_input_usage() 一看到它就把设备归为 HID_GROUP_MULTITOUCH,
//     绑定 hid-multitouch 驱动;该驱动见到 application == HID_DG_TOUCHSCREEN
//     时置 INPUT_MT_DIRECT,最终给 evdev 打上 INPUT_PROP_DIRECT。
//
//  3. Android 的 TouchInputMapper::computeDeviceType() 里:
//        有 DIRECT  -> TOUCH_SCREEN
//        有 POINTER -> POINTER
//        两个都没有 -> 默认 POINTER   (这就是"箭头鼠标"的由来)
//
//  实测踩过的三个坑(都会导致"设备识别正常但收不到事件"):
//
//    (a) 报文长度必须与描述符声明的完全一致,差 1 字节就整包丢弃。
//        本文件用 static_assert 把结构体尺寸钉死在 60 字节。
//    (b) Tip Switch 和 In Range 在描述符里位于同一个字节(bit0/bit1),
//        结构体里必须合并成一个 uint8_t;写成两个 uint8_t 会多 1 字节。
//    (c) BLE 侧必须手工把 Report ID 作为报文第 1 字节发出(见
//        hid_transport_ble.cpp 的 notify);主机靠首字节匹配 Report Map。
//        USB 侧不需要 —— Adafruit 的 sendReport() 内部会自己加。
//
//  报文布局(Report ID 之后共 60 字节 = 10 个槽位 x 6 字节):
//      每槽: [TipSwitch|InRange|6位填充][ContactID][X:u16 LE][Y:u16 LE]
// ============================================================================
#pragma once

#include <stdint.h>

#include "hid_transport.h"   // TOUCH_SLOT_COUNT / TOUCH_MAX_CONTACTS / RID_*

// ---------------------------------------------------------------------------
//  是否把键盘 / 多媒体 / 鼠标集合编进描述符
//
//  ⚠️ 重要:只要描述符里有「键盘」集合,三星手机在连接后就会反复弹
//     「是否连入外接键盘」的对话框,把输入框挡住,导致**手动输入也没法用**。
//     实测:即使断开蓝牙、卸载 App 都还会弹,因为残留记录在系统的蓝牙 HID 服务里。
//
//  由「触摸注入板」的实际情况决定:键盘在这台手机上本来也用不了
//     (中文输入法会把按键变成候选词,不进输入框),所以默认**只保留触摸屏**。
//
//  想研究键盘/音量键时,把下面三个宏改成 1 重新编译即可。
// ---------------------------------------------------------------------------
#ifndef HID_INCLUDE_KEYBOARD
#define HID_INCLUDE_KEYBOARD 0
#endif
#ifndef HID_INCLUDE_CONSUMER
#define HID_INCLUDE_CONSUMER 0
#endif
#ifndef HID_INCLUDE_MOUSE
#define HID_INCLUDE_MOUSE 0
#endif

static const uint8_t HID_REPORT_MAP[] = {
    // ======================= 1) Digitizer / Touch Screen =======================
    0x05, 0x0D,        // Usage Page (Digitizer)
    0x09, 0x04,        // Usage (Touch Screen)
    0xA1, 0x01,        // Collection (Application)

    // ---- Report ID 1: 触点输入报文(10 个槽位)----
    0x85, RID_TOUCHSCREEN,   //   Report ID (1)
    0x09, 0x22,        //   Usage (Finger)
    0xA1, 0x02,        //   Collection (Logical)
    0x09, 0x42,        //     Usage (Tip Switch)
    0x15, 0x00,        //     Logical Minimum (0)
    0x25, 0x01,        //     Logical Maximum (1)
    0x75, 0x01,        //     Report Size (1)
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x09, 0x32,        //     Usage (In Range)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x75, 0x06,        //     Report Size (6)   <- 补齐到整字节
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x03,        //     Input (Const,Var,Abs)
    0x09, 0x51,        //     Usage (Contact Identifier)   关键:触发 hid-multitouch
    0x75, 0x08,        //     Report Size (8)
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x05, 0x01,        //     Usage Page (Generic Desktop)
    0x09, 0x30,        //     Usage (X)
    0x09, 0x31,        //     Usage (Y)
    0x16, 0x00, 0x00,  //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,  //     Logical Maximum (32767)
    0x75, 0x10,        //     Report Size (16)
    0x95, 0x02,        //     Report Count (2)  <- X 与 Y 各一个
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x95, TOUCH_SLOT_COUNT, //     Report Count (10) 把上面这组整体重复 10 次
    0xC0,              //   End Collection (Logical)

    // ---- Feature: 最大触点数 ----
    0x85, RID_FEATURE_MAXCONTACTS,   //   Report ID (5)
    0x09, 0x55,        //   Usage (Contact Count Maximum)
    0x15, 0x00,        //   Logical Minimum (0)
    0x25, TOUCH_MAX_CONTACTS, //  Logical Maximum (10)
    0x75, 0x08,        //   Report Size (8)
    0x95, 0x01,        //   Report Count (1)
    0xB1, 0x02,        //   Feature (Data,Var,Abs)

    // ---- Feature: 设备模式(0=鼠标, 1=触摸屏)----
    0x85, RID_FEATURE_DEVMODE, //   Report ID (6)
    0x09, 0x52,        //   Usage (Device Mode)
    0x15, 0x00,        //   Logical Minimum (0)
    0x25, 0x0A,        //   Logical Maximum (10)
    0x75, 0x08,        //   Report Size (8)
    0x95, 0x01,        //   Report Count (1)
    0xB1, 0x02,        //   Feature (Data,Var,Abs)

    0xC0,              // End Collection (Application)

#if HID_INCLUDE_KEYBOARD
    // ======================= 2) Keyboard =======================
    //  ⚠️ 加回这一整段会让三星弹「是否连入外接键盘」
    0x05, 0x01,        // Usage Page (Generic Desktop)
    0x09, 0x06,        // Usage (Keyboard)
    0xA1, 0x01,        // Collection (Application)
    0x85, RID_KEYBOARD,//   Report ID (2)
    0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
    0x19, 0xE0,        //   Usage Minimum (LeftControl)
    0x29, 0xE7,        //   Usage Maximum (Right GUI)
    0x15, 0x00,        //   Logical Minimum (0)
    0x25, 0x01,        //   Logical Maximum (1)
    0x75, 0x01,        //   Report Size (1)
    0x95, 0x08,        //   Report Count (8)
    0x81, 0x02,        //   Input (Data,Var,Abs)      ; 修饰键
    0x95, 0x01,        //   Report Count (1)
    0x75, 0x08,        //   Report Size (8)
    0x81, 0x01,        //   Input (Const,Array,Abs)   ; 保留字节
    0x95, 0x06,        //   Report Count (6)
    0x75, 0x08,        //   Report Size (8)
    0x15, 0x00,        //   Logical Minimum (0)
    0x25, 0x65,        //   Logical Maximum (101)
    0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
    0x19, 0x00,        //   Usage Minimum (0)
    0x29, 0x65,        //   Usage Maximum (101)
    0x81, 0x00,        //   Input (Data,Array)        ; 6 个按键
    0xC0,              // End Collection
#endif  // HID_INCLUDE_KEYBOARD

#if HID_INCLUDE_CONSUMER
    // ======================= 3) Consumer Control =======================
    0x05, 0x0C,        // Usage Page (Consumer)
    0x09, 0x01,        // Usage (Consumer Control)
    0xA1, 0x01,        // Collection (Application)
    0x85, RID_CONSUMER,//   Report ID (3)
    0x15, 0x00,        //   Logical Minimum (0)
    0x26, 0xFF, 0x03,  //   Logical Maximum (1023)
    0x19, 0x00,        //   Usage Minimum (0)
    0x2A, 0xFF, 0x03,  //   Usage Maximum (1023)
    0x75, 0x10,        //   Report Size (16)
    0x95, 0x01,        //   Report Count (1)
    0x81, 0x00,        //   Input (Data,Array,Abs)
    0xC0,              // End Collection
#endif  // HID_INCLUDE_CONSUMER

#if HID_INCLUDE_MOUSE
    // ======================= 4) Mouse (相对) =======================
    0x05, 0x01,        // Usage Page (Generic Desktop)
    0x09, 0x02,        // Usage (Mouse)
    0xA1, 0x01,        // Collection (Application)
    0x85, RID_MOUSE,   //   Report ID (4)
    0x09, 0x01,        //   Usage (Pointer)
    0xA1, 0x00,        //   Collection (Physical)
    0x05, 0x09,        //     Usage Page (Button)
    0x19, 0x01,        //     Usage Minimum (1)
    0x29, 0x08,        //     Usage Maximum (8)
    0x15, 0x00,        //     Logical Minimum (0)
    0x25, 0x01,        //     Logical Maximum (1)
    0x95, 0x08,        //     Report Count (8)
    0x75, 0x01,        //     Report Size (1)
    0x81, 0x02,        //     Input (Data,Var,Abs)     ; 8 个按键位
    0x05, 0x01,        //     Usage Page (Generic Desktop)
    0x09, 0x30,        //     Usage (X)
    0x09, 0x31,        //     Usage (Y)
    0x09, 0x38,        //     Usage (Wheel)
    0x15, 0x81,        //     Logical Minimum (-127)
    0x25, 0x7F,        //     Logical Maximum (127)
    0x75, 0x08,        //     Report Size (8)
    0x95, 0x03,        //     Report Count (3)
    0x81, 0x06,        //     Input (Data,Var,Rel)
    0xC0,              //   End Collection
#endif  // HID_INCLUDE_MOUSE
    // 注意:这里**不能**再有 0xC0 —— 触摸屏的 Application 集合已经在上面
    // (Feature 报文的后面)闭合过了。多一个会导致 Collection 不配对,
    // 描述符非法 → Android 的 HID 层直接拒绝连接(HID connection state=0),
    // 表现为"蓝牙已连接但内核里没有设备"。实测踩过。
};

static const uint16_t HID_REPORT_MAP_LEN = sizeof(HID_REPORT_MAP);

// ---------------------------------------------------------------------------
//  报文结构体(必须与上面的 Report Map 逐字节对应)
//
//  每个槽位 6 字节:
//    flags:     bit0 = Tip Switch, bit1 = In Range,(其余 6 位填充)
//    contactId: Contact Identifier
//    x, y:      0..32767 的逻辑坐标,小端
// ---------------------------------------------------------------------------
static constexpr uint8_t TOUCH_FLAG_TIP   = 0x01;
static constexpr uint8_t TOUCH_FLAG_RANGE = 0x02;

#pragma pack(push, 1)
struct TouchSlot {
  uint8_t  flags;
  uint8_t  contactId;
  uint16_t x;
  uint16_t y;
};
static_assert(sizeof(TouchSlot) == 6, "TouchSlot 必须为 6 字节");

struct TouchReport {
  TouchSlot slot[TOUCH_SLOT_COUNT];
};
#pragma pack(pop)

// 与描述符声明一致:10 槽 x (1 + 1 + 6 + 8 + 16 + 16) bit = 480 bit = 60 字节
static_assert(sizeof(TouchReport) == TOUCH_SLOT_COUNT * 6,
              "TouchReport 大小与 Report Map 声明不一致 -> 主机会丢弃报文");
