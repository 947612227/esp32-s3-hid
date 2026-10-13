// ============================================================================
//  板载 RGB 灯(GPIO48 WS2812)
// ============================================================================
#include "led.h"
#include "log_buffer.h"

namespace Led {

// ESP32-S3 开发板的板载 WS2812 一般挂在 GPIO48
// (Arduino 框架的 pins_arduino.h 里就是 PIN_NEOPIXEL 48)
#ifndef PIN_RGB_LED
#define PIN_RGB_LED 48
#endif

static uint8_t  s_r = 0, s_g = 0, s_b = 0;
static uint32_t s_holdUntil = 0;      // 0 = 不限时
static bool     s_identify = false;
static uint32_t s_identifyUntil = 0;
static int      s_state = 0;
/**
 * 是否被"手动接管"。
 *
 * ⚠️⚠️ 必须有这个标志 —— 最早没有,结果:
 *      手动设了红色 / 或关灯,手机一连上,状态色就把灯盖成绿色,
 *      用户看着莫名其妙("我明明关了它怎么又亮了")。
 *      手动设过颜色之后必须**锁住**,直到显式 autoMode() 才交还给状态色。
 */
static bool     s_manual = false;

static void write(uint8_t r, uint8_t g, uint8_t b) {
  s_r = r; s_g = g; s_b = b;
#if defined(PIN_NEOPIXEL) || defined(RGB_BUILTIN)
  // Arduino-ESP32 自带 neopixelWrite(pin, r, g, b)
  neopixelWrite(PIN_RGB_LED, r, g, b);
#else
  // 没有 neopixel 支持就退化成普通 GPIO 输出(至少能亮/灭)
  pinMode(PIN_RGB_LED, OUTPUT);
  digitalWrite(PIN_RGB_LED, (r + g + b) > 0 ? HIGH : LOW);
#endif
}

void begin() {
  write(0, 0, 0);
  // 上电闪一下绿灯:证明固件起来了(GPIO48 不一定是灯也无所谓,不会报错)
  write(0, 40, 0);
  delay(120);
  write(0, 0, 0);
  LOGF("[led] 板载 RGB 灯已就绪(GPIO%d)", PIN_RGB_LED);
}

void set(uint8_t r, uint8_t g, uint8_t b, uint32_t holdMs) {
  s_identify = false;
  s_manual = true;                       // 手动接管,状态色不许盖
  write(r, g, b);
  s_holdUntil = holdMs ? (millis() + holdMs) : 0;
}

void off() { set(0, 0, 0, 0); }

void autoMode() {
  s_manual = false;
  s_identify = false;
  s_holdUntil = 0;
  s_state = -1;                          // 强制重画
  setStateColor(0);
}

bool isManual() { return s_manual && !s_identify; }

const char *modeName() {
  if (s_identify) return "找板子";
  if (!s_manual)  return "自动(随状态)";
  if (s_r == 0 && s_g == 0 && s_b == 0) return "关灯(手动)";
  return "手动色";
}

void identify() {
  LOGLN("[led] 找板子模式:快-快-慢 + 绿色常亮");
  // 快 快 慢 三闪(白),这样即使一堆板子同时亮也能挑出来
  const uint16_t on[]  = {90, 90, 400};
  const uint16_t off[] = {110, 110, 200};
  for (int i = 0; i < 3; i++) {
    write(255, 255, 255);
    delay(on[i]);
    write(0, 0, 0);
    delay(off[i]);
  }
  // 最后常亮绿色
  write(0, 200, 0);
  s_identify = true;
  s_identifyUntil = millis() + 60000;   // 亮 60 秒后回到状态色
  s_holdUntil = 0;
}

bool identifying() { return s_identify; }

void setStateColor(int state) {
  if (s_identify) return;               // 找板子期间别被盖掉
  // ⚠️ 手动常亮(holdMs==0)时,状态色**完全不许动它**
  if (s_manual && s_holdUntil == 0) return;
  if (state == s_state) return;
  s_state = state;
  switch (state) {
    case 1:  write(0, 12, 0);  break;   // 已连接:暗绿
    case 2:  write(18, 14, 0); break;   // 执行中:暗黄
    default: write(0, 0, 14);  break;   // 未连接:暗蓝
  }
}

void tick() {
  uint32_t now = millis();
  if (s_identify && s_identifyUntil && now > s_identifyUntil) {
    s_identify = false;
    s_state = -1;                        // 强制重画一次状态色
    setStateColor(0);
    return;
  }
  // 限时的手动色到点了 → **回到自动**(不是留在手动模式)
  if (!s_identify && s_holdUntil && now > s_holdUntil) {
    s_holdUntil = 0;
    s_manual = false;
    s_state = -1;
    setStateColor(0);
  }
}

}  // namespace Led
