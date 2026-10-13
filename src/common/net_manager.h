// ============================================================================
//  NetManager —— WiFi 连接 + Web 管理服务
//
//  两种工作模式:
//    STA:连路由器,电脑通过局域网访问
//    AP :板子自己开热点(STA 连不上时自动回退,保证永远能管理)
//
//  ⚠️ ESP32-S3 的 WiFi 与 BLE 共用射频,同时开启会有共存开销(BLE HID 延迟变差)。
//     固件里必须允许 WiFi modem sleep,否则底层报错并无限重启。
//
//  凭据来自 secrets.h(不进版本库),见 secrets.example.h
// ============================================================================
#pragma once

#include <Arduino.h>

// ---- 脚本执行请求(异步:HTTP 立刻返回,由 loop 真正执行)----
struct RunRequest {
  int      index = -1;      // 要执行的脚本序号
  uint16_t repeat = 1;      // 重复执行次数
  uint32_t intervalMs = 0;  // 两次之间的间隔
};

void requestScriptRun(uint8_t index, uint16_t repeat = 1,
                      uint32_t intervalMs = 0);
bool takeScriptRunRequest(RunRequest &req);

// ---- 脚本执行状态(供 Web 查询进度)----
struct RunState {
  bool     running = false;
  uint8_t  index = 0;
  uint8_t  step = 0;
  uint8_t  totalSteps = 0;
  uint16_t repeatDone = 0;
  uint16_t repeatTotal = 0;
  uint32_t elapsedMs = 0;
  char     lastName[24] = {0};
  char     lastError[64] = {0};
  uint16_t runCount = 0;
  bool     lastOk = true;
};
const RunState &scriptRunState();
void requestScriptStop();

// main.cpp 的 loop() 调用:真正执行脚本与定时任务(带进度与可中断)
void netServiceScripts();

// ---- 调度(定时循环执行)----
bool scheduleActive();
// durationMs = 0 表示不限时长(手动停)
void requestSchedule(int index, uint32_t intervalMs, uint32_t durationMs = 0);
/** 定时循环剩余毫秒(0 = 不限或未开启) */
uint32_t scheduleRemainMs();
void requestScheduleStop();

class NetManager {
public:
  bool begin();
  void loop();
  void stop();

  bool staConnected() const;
  bool apActive() const;
  String ip() const;
  String modeName() const;
  String url() const;

  /**
   * 往控制中心报一个"流程事件"(流程里写了 `notify ...` 时调用)。
   *
   * 为什么走服务器而不是直接推手机:板子在内网、人在外面,
   * 只有服务器既能收到、又能转发(webhook)。
   * 服务器存进 board_events 表,配了 webhook 就顺手推一下。
   */
  bool reportFlowEvent(const char *flowName, const char *message);

  /** 组装 /api/status 的 JSON(HTTP 和 BLE 控制通道共用) */
  String statusJson();

  // ---- 给 BLE 控制通道用(板子没网时走蓝牙)----

  /** 排一个"跑这个流程"的请求(真正执行在 loop 里的 netServiceScripts) */
  void requestRun(uint8_t index);

  /** 最近日志的 JSON(和 /api/logs 同一份) */
  String logsJson();

  /**
   * 只返回**最后 n 行**日志的 JSON。
   * BLE 控制通道用它 —— 整份日志分片太多会丢片(见 ble_command.cpp 的注释)。
   */
  String logsJsonTail(int n);

  /** 清空日志 */
  void   clearLogs();

  /** 某个流程的文本内容(和 /api/script 同一份) */
  String scriptText(uint8_t index);

  /** 停掉正在跑的定时/频率循环(和 /api/schedule/stop 同一件事) */
  void   stopSchedulePublic();

private:
  bool _started = false;
  bool _ap = false;
};

NetManager &netManager();
