// ============================================================================
//  LogBuffer 实现
// ============================================================================
#include "log_buffer.h"

LogBuffer Log;

void LogBuffer::push(const char *line) {
  strncpy(_buf[_head], line, LOG_LINE_LEN - 1);
  _buf[_head][LOG_LINE_LEN - 1] = '\0';
  _head = (uint16_t)((_head + 1) % LOG_RING_LINES);
  if (_count < LOG_RING_LINES) _count++;
}

void LogBuffer::line(const char *fmt, ...) {
  char tmp[LOG_LINE_LEN];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(tmp, sizeof(tmp), fmt, ap);
  va_end(ap);

  // ⚠️ vsnprintf 是按**字节**截断的 —— 中文一个字 3 字节,
  //    第 160 个字节很可能落在字符中间,产生**非法 UTF-8**,
  //    结果 Web/App 读 /api/logs 时整个响应解析失败
  //    (实测:UnicodeDecodeError: invalid continuation byte)。
  //    这里把结尾残缺的那个字符去掉。
  {
    size_t len = strlen(tmp);
    if (len > 0) {
      // ⚠️ 从**最后一个字节**往回找,不能从 strlen() 开始 ——
      //    tmp[len] 是 '\0',不是最后一个字符(第一版就栽在这,
      //    判断永远不成立,截断的字符照样留着)。
      size_t i = len - 1;
      while (i > 0 && ((unsigned char)tmp[i] & 0xC0) == 0x80) i--;  // 退到该字符首字节
      unsigned char c = (unsigned char)tmp[i];
      size_t need = (c < 0x80) ? 1
                  : ((c & 0xE0) == 0xC0 ? 2 : ((c & 0xF0) == 0xE0 ? 3 : 4));
      if (i + need > len) tmp[i] = '\0';   // 这个字符不完整 → 整块砍掉
    }
  }

  // 同时打到串口(带换行)。
  // ⚠️ 串口 115200 每毫秒只能发 ~11.5 字节:日志很密时(如 repeat 600 + wait 3,每轮 3 行)
  //    println 会按串口速率阻塞,实测每行 ~1ms,这种空循环会被拖慢一倍多。
  //    正常流程步与步之间有点击/等待(上百毫秒),影响可以忽略。
  //    别用 availableForWrite() 做"放不下就跳过":这版 core 里它始终报 ~4040 空闲,
  //    实测不反映真实占用,跳过逻辑永远不会触发。
  Serial.println(tmp);

  // 存进环形缓冲(按行拆开,方便 Web 端逐行显示)
  char *p = tmp;
  while (*p) {
    char *nl = strchr(p, '\n');
    if (nl) *nl = '\0';
    if (*p) push(p);
    if (!nl) break;
    p = nl + 1;
  }
}

void LogBuffer::clear() {
  _head = 0;
  _count = 0;
}

void LogBuffer::forEach(void (*cb)(const char *line, void *ctx),
                        void *ctx) const {
  // 从最旧的一行开始遍历
  uint16_t start = (_count < LOG_RING_LINES)
                       ? 0
                       : _head;   // 满了:最旧的正好是 _head
  for (uint16_t i = 0; i < _count; i++) {
    uint16_t idx = (uint16_t)((start + i) % LOG_RING_LINES);
    cb(_buf[idx], ctx);
  }
}

const char *LogBuffer::at(uint16_t i) const {
  if (i >= _count) return "";
  uint16_t start = (_count < LOG_RING_LINES) ? 0 : _head;
  return _buf[(start + i) % LOG_RING_LINES];
}
