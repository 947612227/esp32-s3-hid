// ============================================================================
//  触摸槽位数与逻辑坐标范围(Report Map 里声明的必须与此一致)
//
//  单独成文件:流程解析器(script_parser.cpp)也要用 TOUCH_LOGICAL_MAX,
//  而它要能脱离 Arduino 在电脑上编译单元测试,不能去 include hid_transport.h。
// ============================================================================
#pragma once

#include <stdint.h>

static constexpr uint8_t TOUCH_SLOT_COUNT   = 10;
static constexpr uint8_t TOUCH_MAX_CONTACTS = 10;
static constexpr int32_t TOUCH_LOGICAL_MAX  = 32767;
