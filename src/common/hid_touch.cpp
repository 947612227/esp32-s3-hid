// ============================================================================
//  HidTouch 实现 —— 与传输无关的手势引擎
// ============================================================================
#include "hid_touch.h"

HidTouch Touch;

bool HidTouch::begin() {
  _ready = hidTransport().begin();
  return _ready;
}

// ---------------------------------------------------------------------------
//  底层触点操作
// ---------------------------------------------------------------------------
void HidTouch::touchDown(uint8_t slot, float nx, float ny, uint8_t contactId) {
  if (slot >= TOUCH_SLOT_COUNT) return;
  _down[slot]    = true;
  _inRange[slot] = true;
  _cid[slot]     = contactId;
  _x[slot]       = normToLogical(nx);
  _y[slot]       = normToLogical(ny);
}

void HidTouch::touchUp(uint8_t slot) {
  if (slot >= TOUCH_SLOT_COUNT) return;
  _down[slot] = false;
}

bool HidTouch::flush() {
  uint8_t  tipMask = 0;
  uint8_t  inMask  = 0;
  for (uint8_t i = 0; i < TOUCH_SLOT_COUNT; i++) {
    if (_down[i])    tipMask |= (uint8_t)(1u << i);
    if (_inRange[i]) inMask  |= (uint8_t)(1u << i);
  }
  return hidTransport().sendTouch(tipMask, inMask, _cid, _x, _y);
}

void HidTouch::sync() {
  flush();
  // 流控:等上一次发送真正完成再继续,否则 BLE 侧会整批丢包
  uint32_t t0 = millis();
  while (!hidTransport().readyToSend() && millis() - t0 < 40) {
    delay(1);
  }
  delay(REPORT_THROTTLE_MS);
}

void HidTouch::releaseAll() {
  for (uint8_t i = 0; i < TOUCH_SLOT_COUNT; i++) {
    _down[i]    = false;
    _inRange[i] = false;
    _x[i]       = 0;
    _y[i]       = 0;
  }
  flush();
  delay(30);
  flush();   // 再补一帧全空,确保内核完成 up 事件
}

bool HidTouch::active() const {
  for (uint8_t i = 0; i < TOUCH_SLOT_COUNT; i++) {
    if (_down[i]) return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
//  路径插值执行器
// ---------------------------------------------------------------------------
void HidTouch::runPath(PathFn moveFn, void *ctx, uint32_t durationMs,
                       uint8_t nPts, uint16_t minStepMs, uint16_t maxStepMs) {
  uint32_t total = durationMs > 0 ? durationMs : 1;
  uint32_t steps = total / (uint32_t)maxStepMs;
  if (steps < nPts) steps = nPts;
  if (steps < 2)    steps = 2;
  if (steps > 40)   steps = 40;

  float pts[2 * TOUCH_SLOT_COUNT];

  for (uint32_t s = 0; s <= steps; s++) {
    float t = (float)s / (float)steps;
    for (uint8_t i = 0; i < 2 * TOUCH_SLOT_COUNT; i++) pts[i] = -1.0f;
    if (!moveFn(ctx, (uint8_t)s, (uint8_t)steps, t, pts, TOUCH_SLOT_COUNT)) {
      break;
    }
    for (uint8_t i = 0; i < TOUCH_SLOT_COUNT; i++) {
      if (pts[2 * i] < 0.0f) {
        _down[i] = false;
        continue;
      }
      touchDown(i, pts[2 * i], pts[2 * i + 1], _cid[i]);
    }
    flush();

    uint32_t stepMs = total / steps;
    if (stepMs < minStepMs) stepMs = minStepMs;
    if (stepMs > maxStepMs) stepMs = maxStepMs;
    delay((uint32_t)(stepMs * TOUCH_TIME_SCALE));
  }
}

// ---------------------------------------------------------------------------
//  路径定义
// ---------------------------------------------------------------------------
namespace {

struct LineCtx {
  float x1, y1, x2, y2;
  float ax, ay;
  float amp;
};

bool lineMove(void *vctx, uint8_t step, uint8_t total, float t, float *pts,
              uint8_t nPts) {
  (void)step; (void)total; (void)nPts;
  LineCtx *c = (LineCtx *)vctx;
  float x = c->x1 + (c->x2 - c->x1) * t;
  float y = c->y1 + (c->y2 - c->y1) * t;
  float envelope = sinf(t * (float)M_PI);
  x += c->ax * c->amp * envelope;
  y += c->ay * c->amp * envelope;
  pts[0] = x;
  pts[1] = y;
  return true;
}

struct QuadCtx {
  float x1, y1, cx, cy, x2, y2;
};

bool quadMove(void *vctx, uint8_t step, uint8_t total, float t, float *pts,
              uint8_t nPts) {
  (void)step; (void)total; (void)nPts;
  QuadCtx *c = (QuadCtx *)vctx;
  float u = 1.0f - t;
  float x = u * u * c->x1 + 2.0f * u * t * c->cx + t * t * c->x2;
  float y = u * u * c->y1 + 2.0f * u * t * c->cy + t * t * c->y2;
  pts[0] = x;
  pts[1] = y;
  return true;
}

struct TwoCtx {
  float x1a, y1a, x2a, y2a;
  float x1b, y1b, x2b, y2b;
};

bool twoMove(void *vctx, uint8_t step, uint8_t total, float t, float *pts,
             uint8_t nPts) {
  (void)step; (void)total; (void)nPts;
  TwoCtx *c = (TwoCtx *)vctx;
  pts[0] = c->x1a + (c->x2a - c->x1a) * t;
  pts[1] = c->y1a + (c->y2a - c->y1a) * t;
  pts[2] = c->x1b + (c->x2b - c->x1b) * t;
  pts[3] = c->y1b + (c->y2b - c->y1b) * t;
  return true;
}

}  // namespace

// ---------------------------------------------------------------------------
//  手势封装
// ---------------------------------------------------------------------------
void HidTouch::tap(float nx, float ny, uint32_t holdMs) {
  _cid[0] = 1;
  touchDown(0, nx, ny, 1);
  sync();
  delay((uint32_t)(holdMs * TOUCH_TIME_SCALE));
  _down[0] = false;
  sync();
  _inRange[0] = false;
  flush();
  delay(20);
}

void HidTouch::doubleTap(float nx, float ny, uint32_t gapMs) {
  tap(nx, ny);
  delay((uint32_t)(gapMs * TOUCH_TIME_SCALE));
  tap(nx, ny);
}

void HidTouch::longPress(float nx, float ny, uint32_t holdMs) {
  tap(nx, ny, holdMs);
}

void HidTouch::swipe(float x1, float y1, float x2, float y2,
                     uint32_t durationMs) {
  LineCtx c;
  c.x1 = x1; c.y1 = y1; c.x2 = x2; c.y2 = y2;
  float dx = x2 - x1, dy = y2 - y1;
  float len = sqrtf(dx * dx + dy * dy);
  if (len > 0.0001f) {
    c.ax = -dy / len;
    c.ay =  dx / len;
  } else {
    c.ax = 0; c.ay = 0;
  }
  c.amp = 0.004f;

  _cid[0] = 1;
  touchDown(0, x1, y1, 1);
  sync();
  runPath(lineMove, &c, durationMs, 8, 5, 16);
  _down[0] = false;
  sync();
  _inRange[0] = false;
  flush();
  delay(20);
}

void HidTouch::curveSwipe(float x1, float y1, float cx, float cy, float x2,
                          float y2, uint32_t durationMs) {
  QuadCtx c;
  c.x1 = x1; c.y1 = y1; c.cx = cx; c.cy = cy; c.x2 = x2; c.y2 = y2;

  _cid[0] = 1;
  touchDown(0, x1, y1, 1);
  sync();
  runPath(quadMove, &c, durationMs, 8, 5, 16);
  _down[0] = false;
  sync();
  _inRange[0] = false;
  flush();
  delay(20);
}

void HidTouch::twoFingerSwipe(float x1a, float y1a, float x2a, float y2a,
                              float x1b, float y1b, float x2b, float y2b,
                              uint32_t durationMs) {
  TwoCtx c;
  c.x1a = x1a; c.y1a = y1a; c.x2a = x2a; c.y2a = y2a;
  c.x1b = x1b; c.y1b = y1b; c.x2b = x2b; c.y2b = y2b;

  _cid[0] = 1;
  _cid[1] = 2;
  // 两指分开两个报文按下,否则部分机型会合并成单指
  touchDown(0, x1a, y1a, 1);
  sync();
  touchDown(1, x1b, y1b, 2);
  sync();

  runPath(twoMove, &c, durationMs, 8, 5, 16);

  _down[0] = false;
  flush();
  delay(5);
  _down[1] = false;
  sync();
  _inRange[0] = false;
  _inRange[1] = false;
  flush();
  delay(20);
}

void HidTouch::pinch(float cx, float cy, float startGapN, float endGapN,
                     uint32_t durationMs) {
  twoFingerSwipe(cx - startGapN / 2.0f, cy, cx - endGapN / 2.0f, cy,
                 cx + startGapN / 2.0f, cy, cx + endGapN / 2.0f, cy,
                 durationMs);
}

// ---------------------------------------------------------------------------
//  键盘 / Consumer / 鼠标
// ---------------------------------------------------------------------------
void HidTouch::keyDown(uint8_t usage) {
  KeyboardReport rpt = {};
  rpt.keys[0] = usage;
  hidTransport().sendKeyboard(rpt);
}

void HidTouch::keyUp(uint8_t usage) {
  (void)usage;
  KeyboardReport rpt = {};
  hidTransport().sendKeyboard(rpt);
}

void HidTouch::keyTap(uint8_t usage) {
  keyDown(usage);
  delay(25);
  keyUp(usage);
  delay(20);
}

void HidTouch::consumerTap(uint16_t usage) {
  ConsumerReport rpt = {};
  rpt.usage = usage;
  hidTransport().sendConsumer(rpt);
  delay(30);
  rpt.usage = 0;
  hidTransport().sendConsumer(rpt);
  delay(20);
}

void HidTouch::mouseMove(int8_t dx, int8_t dy) {
  MouseReport rpt = {};
  rpt.dx = dx;
  rpt.dy = dy;
  hidTransport().sendMouse(rpt);
  delay(10);
}

void HidTouch::mouseClick(uint8_t button) {
  MouseReport rpt = {};
  rpt.buttons = button;
  hidTransport().sendMouse(rpt);
  delay(30);
  rpt.buttons = 0;
  hidTransport().sendMouse(rpt);
  delay(20);
}
