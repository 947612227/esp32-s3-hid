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
// 四色轮播自检:step 0..3 = 红/黄/绿/灭,4 = 结束
static bool     s_test = false;
static uint8_t  s_testStep = 0;
static uint32_t s_testNextAt = 0;

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

/**
 * 状态灯配色 —— 按需求定的四态。
 *
 * ⚠️ 亮度都压得比较低(不是 255 满亮):
 *    这是**状态指示**,不是照明。满亮在桌面很刺眼,夜里更难受。
 *    想临时亮满色用 `LED r g b`(手动模式,见 set())。
 */
void setState(int state) {
  if (s_test) return;                   // 自检期间谁都不许改灯
  if (s_identify) return;               // 找板子期间别被盖掉
  // ⚠️ 手动常亮(holdMs==0)时,状态色**完全不许动它**
  if (s_manual && s_holdUntil == 0) return;
  // ⚠️⚠️ 限时色(比如 finished() 的 5 秒绿)在保持期内也**不许盖**。
  //      踩过的坑:finished() 刚设好绿,主循环下一轮就 setState(ST_IDLE)
  //      把它盖成红 ⇒ 用户根本看不到"执行结束变绿"。
  if (s_holdUntil && millis() < s_holdUntil) return;
  if (state == s_state) return;
  s_state = state;
  // 每次状态变化打一行 —— 没有眼睛也能验证状态机对不对(实测很有用)
  Serial.printf("[led] 状态色 → %s\n",
                state == ST_IDLE ? "红(待机)" : state == ST_RUNNING ? "黄(运行中)"
                : state == ST_DONE ? "绿(刚跑完)" : "灭(闲置)");
  switch (state) {
    case ST_IDLE:    write(60, 0, 0);   break;   // 待机:红
    case ST_RUNNING: write(60, 45, 0);  break;   // 运行:黄
    case ST_DONE:    write(0, 60, 0);   break;   // 结束:绿
    default:         write(0, 0, 0);    break;   // 闲置:灭
  }
}

void setStateColor(int state) { setState(state); }

void colorTest() {
  s_test = true;
  s_testStep = 0;
  s_testNextAt = 0;          // 立刻亮第一个色
  s_identify = false;
  Serial.println(F("[led] 四色自检:红→黄→绿→灭"));
}

bool testing() { return s_test; }

void finished() {
  Serial.println(F("[led] 执行结束 → 绿 5 秒"));
  // 绿 5 秒,然后 tick() 会自动回待机
  s_identify = false;
  s_manual = false;
  write(0, 60, 0);
  s_state = ST_DONE;
  s_holdUntil = millis() + 5000;
}

void tick() {
  uint32_t now = millis();

  // ---- 四色轮播自检(优先级最高,期间别的都不许改灯)----
  if (s_test) {
    if (now >= s_testNextAt) {
      switch (s_testStep) {
        case 0: write(60, 0, 0);  Serial.println(F("[led] 自检 1/4 红"));   break;
        case 1: write(60, 45, 0); Serial.println(F("[led] 自检 2/4 黄"));   break;
        case 2: write(0, 60, 0);  Serial.println(F("[led] 自检 3/4 绿"));   break;
        default: write(0, 0, 0);  Serial.println(F("[led] 自检 4/4 灭"));   break;
      }
      s_testStep++;
      s_testNextAt = now + 1500;
      if (s_testStep > 3) {
        s_test = false;
        s_state = -1;          // 让主循环下一轮重新报真实状态
        Serial.println(F("[led] 自检结束,回到自动"));
      }
    }
    return;                    // 自检期间不处理其它
  }
  if (s_identify && s_identifyUntil && now > s_identifyUntil) {
    s_identify = false;
    s_state = -1;                        // 强制重画一次状态色
    setStateColor(0);
    return;
  }
  // 限时色(含 finished() 的绿)到点了 → 回自动。
  // ⚠️ 这里只把 s_state 置成 -1,让 main loop 下一轮重新报真实状态;
  //    不去猜"现在该是什么色" —— 那需要访问 HID/脚本状态,层次不对。
  if (!s_identify && s_holdUntil && now > s_holdUntil) {
    s_holdUntil = 0;
    s_manual = false;
    s_state = -1;
  }
}

}  // namespace Led
