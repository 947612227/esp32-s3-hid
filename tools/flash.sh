#!/usr/bin/env bash
# ============================================================================
#  烧录一块**新板子**(或重新刷当前板子)
#
#  用法
#  ────────────────────────────────────────────────────────────────────────
#    ./tools/flash.sh                 # 烧 BLE 版(推荐)
#    ./tools/flash.sh usb             # 烧 USB 版
#    ./tools/flash.sh ble fresh       # 先全片擦除再烧(**新板子推荐**)
#    ./tools/flash.sh ble "" /dev/cu.usbmodem1234    # 手动指定串口
#
#  前置
#  ────────────────────────────────────────────────────────────────────────
#    1. 板子必须是 **ESP32-S3 N8R8**:8MB Flash + **8MB PSRAM(OPI)**
#       板子插到 Mac 上,应该出现 /dev/cu.usbmodem*
#    2. secrets.h 里配好你要用的 WiFi(或先烧进去,再用 AP 模式配网)
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_NAME="${1:-ble}"
MODE="${2:-}"
PORT="${3:-}"

case "$ENV_NAME" in
  ble|usb|usb_touchtest) ;;
  *) echo "❌ 环境名只能是 ble / usb / usb_touchtest"; exit 1 ;;
esac

# ---- 1. 探测串口 ----
if [ -z "$PORT" ]; then
  PORT=$(ls /dev/cu.usbmodem* /dev/cu.usbserial* /dev/cu.wchusbserial* 2>/dev/null | head -1 || true)
fi
if [ -z "$PORT" ]; then
  echo "❌ 没找到串口。检查:"
  echo "     · 板子用 **USB-C 数据线**插到 Mac 了吗(有些线只供电不传数据)"
  echo "     · 板子上有没有独立的 USB 口?ESP32-S3 是**原生 USB**,不要接 UART 那个"
  echo "     · 试一下:ls /dev/cu.*"
  exit 1
fi
echo "  串口: $PORT"
echo "  环境: $ENV_NAME"

# ---- 2. 编译 ----
echo
echo "== 1/4 编译 =="
pio run -e "$ENV_NAME" 2>&1 | tail -4

# ---- 3. (可选)全片擦除 ----
if [ "$MODE" = "fresh" ]; then
  echo
  echo "== 2/4 全片擦除(新板子 / 换分区表时必须)=="
  echo "   ⚠️ 这会把板子上所有脚本、配网信息一起清掉"
  pio run -e "$ENV_NAME" -t erase --upload-port "$PORT" 2>&1 | tail -3
else
  echo
  echo "== 2/4 跳过擦除(要擦除加 fresh 参数)=="
fi

# ---- 4. 烧录 ----
echo
echo "== 3/4 烧录 =="
pio run -e "$ENV_NAME" -t upload --upload-port "$PORT" 2>&1 | tail -6

# ---- 5. 提示 ----
echo
echo "== 4/4 完成 =="
cat <<'TIP'
  ✅ 烧录完成。接下来:

  ① 看板子活没活 —— 等 10 秒,然后:
       curl -s http://192.168.5.37/api/status        (把 IP 换成你实际的)
     不知道 IP?看路由器的 DHCP 列表,或用:
       ./tools/find_board.sh
     板子上会亮一个 AP:ESP32-Touch-xxxx(连不上 WiFi 时的兜底)

  ② ⚠️ 原生 USB 口**看不到串口日志**
     (固件里 -DARDUINO_USB_CDC_ON_BOOT=0,Serial 走的是 UART0 那两个引脚)
     想看日志:
       · 网络:curl -s http://<板子IP>/api/logs
       · 或接 USB-TTL 到 UART0(GPIO43/44)

  ③ 到 App 里「🔍 自动查找板子」把它认出来

  ④ 第一次上电如果 WiFi 连不上 → 板子开热点 ESP32-Touch-xxxx(密码 12345678)
     连上去访问 http://192.168.4.1/ 配网
TIP
