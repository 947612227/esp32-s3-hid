// ============================================================================
//  LogBuffer —— 把日志同时写到串口和内存环形缓冲
//
//  为什么需要:板子插上手机后就没有串口可见了,而网页上要能看到运行日志
//  (脚本执行、WiFi 状态、错误)。这里维护最近 N 行,供 Web 接口读取。
//
//  用法:代码里用 LOGF / LOGLN 替代 Serial.printf / Serial.println 即可,
//        带日志的旧代码不需要全改 —— 只改关键路径。
// ============================================================================
#pragma once

#include <Arduino.h>

#ifndef LOG_LINE_LEN
#define LOG_LINE_LEN 160      // 单行最大长度(超出截断)
#endif
#ifndef LOG_RING_LINES
// 保留最近多少行。
// ⚠️ 80 行不太够 —— 一个 27 步的流程会写 30+ 行日志,
//    两次执行就把前面的顶掉了。提到 150(内存 +11KB,板子还有 130KB+ 空闲)。
#define LOG_RING_LINES 150
#endif

class LogBuffer {
public:
  // 追加一行(自动加换行,同时输出到串口)
  void line(const char *fmt, ...);

  // 清空
  void clear();

  // 把最近日志按行回调出去(从小到大:旧 -> 新)
  void forEach(void (*cb)(const char *line, void *ctx), void *ctx) const;

  // 直接取快照(供 HTTP JSON 拼接)
  uint16_t size() const { return _count; }
  const char *at(uint16_t i) const;

private:
  void push(const char *line);

  char     _buf[LOG_RING_LINES][LOG_LINE_LEN];
  uint16_t _head = 0;    // 下一个写入位置
  uint16_t _count = 0;   // 已写入行数(封顶 LOG_RING_LINES)
};

extern LogBuffer Log;

// 便捷宏:与 Serial.printf/println 用法一致
#define LOGF(...) Log.line(__VA_ARGS__)
#define LOGLN(s)  Log.line("%s", s)
