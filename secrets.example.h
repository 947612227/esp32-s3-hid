// 把本文件复制为 secrets.h 并填入你的 WiFi 信息。
// secrets.h 已加入 .gitignore,不会进版本库。
#pragma once

#define WIFI_SSID "你的WiFi名"
#define WIFI_PASS "你的WiFi密码"

// 站模式连不上时,板子会开这个热点,电脑直连即可
#define AP_SSID_PREFIX "ESP32-Touch"
#define AP_PASS "12345678"          // 至少 8 位
