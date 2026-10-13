// ============================================================================
//  HidTouch —— 与传输无关的手势引擎
//  把"点击/长按/滑动/曲线/双指"翻译成触摸报文序列,通过 HidTransport 发出。
// ============================================================================
#pragma once

#include <Arduino.h>

#include "hid_transport.h"

// 未指定时长时的默认值(毫秒)
static constexpr uint32_t DEFAULT_TAP_MS   = 40;
static constexpr uint32_t DEFAULT_HOLD_MS  = 900;
static constexpr uint32_t DEFAULT_SWIPE_MS = 220;

// 报文节流:两次触摸报文之间的最小间隔(毫秒)
static constexpr uint16_t REPORT_THROTTLE_MS = 4;

// 全局时间缩放,便于整体调快/调慢
#ifndef TOUCH_TIME_SCALE
#define TOUCH_TIME_SCALE 1.0f
#endif

// 路径插值回调:往 pts 里填 [x0,y0, x1,y1, ...],某槽位填负数表示本步不参与
typedef bool (*PathFn)(void *ctx, uint8_t step, uint8_t total, float t,
                       float *pts, uint8_t nPts);

class HidTouch {
public:
  // 初始化底层传输(USB 或 BLE,由构建环境决定)
  bool begin();

  // ---- 底层触点操作 ----
  void touchDown(uint8_t slot, float nx, float ny, uint8_t contactId);
  void touchUp(uint8_t slot);
  bool flush();
  void releaseAll();
  void sync();

  // ---- 常用手势 ----
  void tap(float nx, float ny, uint32_t holdMs = DEFAULT_TAP_MS);
  void doubleTap(float nx, float ny, uint32_t gapMs = 90);
  void longPress(float nx, float ny, uint32_t holdMs = DEFAULT_HOLD_MS);
  void swipe(float x1, float y1, float x2, float y2,
             uint32_t durationMs = DEFAULT_SWIPE_MS);
  void curveSwipe(float x1, float y1, float cx, float cy, float x2, float y2,
                  uint32_t durationMs = DEFAULT_SWIPE_MS);
  void twoFingerSwipe(float x1a, float y1a, float x2a, float y2a,
                      float x1b, float y1b, float x2b, float y2b,
                      uint32_t durationMs = DEFAULT_SWIPE_MS);
  void pinch(float cx, float cy, float startGapN, float endGapN,
             uint32_t durationMs = DEFAULT_SWIPE_MS);

  // ---- 键盘 / Consumer / 鼠标 ----
  void keyTap(uint8_t usage);
  void keyDown(uint8_t usage);
  void keyUp(uint8_t usage);
  void consumerTap(uint16_t usage);
  void mouseMove(int8_t dx, int8_t dy);
  void mouseClick(uint8_t button = 1);

  // ---- 状态 ----
  bool ready() const { return _ready; }
  bool active() const;
  const char *transportName() const { return hidTransport().name(); }

private:
  void runPath(PathFn moveFn, void *ctx, uint32_t durationMs, uint8_t nPts,
               uint16_t minStepMs, uint16_t maxStepMs);

  bool     _ready = false;
  bool     _down[TOUCH_SLOT_COUNT]    = {false};
  bool     _inRange[TOUCH_SLOT_COUNT] = {false};
  uint8_t  _cid[TOUCH_SLOT_COUNT]     = {0};
  uint16_t _x[TOUCH_SLOT_COUNT]       = {0};
  uint16_t _y[TOUCH_SLOT_COUNT]       = {0};
};

// 归一化(0..1)坐标转逻辑坐标
inline uint16_t normToLogical(float n) {
  if (n < 0.0f) n = 0.0f;
  if (n > 1.0f) n = 1.0f;
  return (uint16_t)(n * (float)TOUCH_LOGICAL_MAX + 0.5f);
}

extern HidTouch Touch;
