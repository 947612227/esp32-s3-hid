#!/usr/bin/env bash
# ===========================================================================
#  触摸注入板 · Mac / Linux 一键烧录
#
#  用法:
#    chmod +x flash-mac-linux.sh
#    ./flash-mac-linux.sh              # 烧 BLE 版
#    ./flash-mac-linux.sh usb          # 烧 USB 版
#    ./flash-mac-linux.sh touchtest    # 烧自检版
# ===========================================================================
set -euo pipefail
cd "$(dirname "$0")"

WHICH="${1:-ble}"
case "$WHICH" in
  ble)       FW="touchpad-ble-full.bin" ;;
  usb)       FW="touchpad-usb-full.bin" ;;
  touchtest) FW="touchpad-usb_touchtest-full.bin" ;;
  *) echo "❌ 参数只能是 ble / usb / touchtest"; exit 1 ;;
esac

echo
echo "================================"
echo "  触摸注入板 · 烧录"
echo "================================"
echo

[ -f "$FW" ] || { echo "❌ 找不到 $FW —— 把它和本脚本放同一目录"; exit 1; }
echo "  固件: $FW"

# ---- esptool ----
if ! command -v esptool.py >/dev/null 2>&1 && ! command -v esptool >/dev/null 2>&1; then
  echo "  没装 esptool,正在装…"
  python3 -m pip install --user esptool || { echo "❌ 装不上,请先装 Python 3"; exit 1; }
fi
ESPTOOL=$(command -v esptool.py || command -v esptool)

# ---- 串口 ----
PORT="${2:-}"
if [ -z "$PORT" ]; then
  PORT=$(ls /dev/cu.usbmodem* /dev/ttyUSB* /dev/ttyACM* /dev/cu.usbserial* 2>/dev/null | head -1 || true)
fi
[ -n "$PORT" ] || {
  echo "❌ 没找到串口。检查:"
  echo "     · 板子用**数据线**插到电脑了吗(有些线只供电)"
  echo "     · 插的是「原生 USB」口,不是 UART 口"
  echo "     · 按住 BOOT 键,点一下 RST,松开 BOOT,再试"
  exit 1
}
echo "  串口: $PORT"
echo
echo "  开始烧录(约 30 秒)…"
"$ESPTOOL" --chip esp32s3 --port "$PORT" --baud 921600 \
  write_flash -z --flash_mode keep --flash_freq keep --flash_size 8MB \
  0x0 "$FW"

echo
echo "================================"
echo "  ✅ 烧录完成"
echo "================================"
cat <<TIP

  接下来:
   1. 等 10 秒,板子会连 WiFi
   2. 手机上蓝牙搜 ESP32-Touch 并配对
   3. App 里「🔍 自动查找板子」

  连不上 WiFi? 板子会自己开热点:
    SSID  ESP32-Touch-xxxx
    密码  12345678
    连上去访问 http://192.168.4.1/ 配网
TIP
