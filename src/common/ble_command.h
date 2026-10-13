#pragma once
#include <Arduino.h>

/**
 * BLE 控制通道的**命令处理**(纯逻辑,不碰 NimBLE)。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  为什么单独一层
 *
 *  GATT 服务只能在 ble 环境编译(NimBLE 依赖),但**命令逻辑是通用的** ——
 *  放在 src/ble/ 里的话 usb 环境编不过(common 是全环境编译的)。
 *  所以拆开:ble 那边只管收包/分包,这里只管"收到一句话该干什么"。
 *
 *  协议(文本,方便调试):
 *     请求:  PING | STATUS | LIST | GET <n> | RUN <n> | STOP
 *            | LOG | LOGCLR | SAVE <n>\n<内容>
 *     响应:  一行 OK 或 JSON;出错了回 ERR <原因>
 * ══════════════════════════════════════════════════════════════════════
 */
String bleHandleCommand(const String &req);
