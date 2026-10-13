#!/usr/bin/env bash
# ============================================================================
#  列出当前插着的所有 ESP32-S3,并给出 MAC / Flash / 名字
#
#  为什么需要:两块板子都叫 "USB Single Serial"、都是 CH343,
#  /dev/cu.usbmodemXXXX 里的数字也没规律 —— 光看设备名根本分不清谁是谁。
#  **只有 MAC 是可靠标识**。
#
#  用法:./tools/which-board.sh
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."
REG="tools/board-registry.txt"

name_of() {   # $1 = mac(小写)
  [ -f "$REG" ] || { echo ""; return; }
  awk -v m="$1" 'tolower($1)==m {print $2; exit}' "$REG" 2>/dev/null
}

PORTS=$(ls /dev/cu.usbmodem* /dev/cu.usbserial* /dev/cu.wchusbserial* 2>/dev/null)
if [ -z "$PORTS" ]; then echo "❌ 没找到任何串口"; exit 1; fi

echo "插着的板子:"
echo
for P in $PORTS; do
  INFO=$(esptool.py --port "$P" flash_id 2>&1)
  MAC=$(echo "$INFO"  | grep -oE 'MAC: ([0-9a-f:]{17})' | awk '{print tolower($2)}')
  CHIP=$(echo "$INFO" | grep -m1 'Chip is' | sed 's/.*Chip is //')
  FLASH=$(echo "$INFO"| grep -oE 'Detected flash size: [0-9]+MB' | awk '{print $4}')
  if [ -z "$MAC" ]; then
    echo "  $P"
    echo "     ⚠️ 探测失败(可能没插好/被占用)"
    echo
    continue
  fi
  NM=$(name_of "$MAC")
  echo "  $P"
  echo "     MAC    : $MAC"
  echo "     芯片   : $CHIP"
  echo "     Flash  : $FLASH"
  if [ -n "$NM" ]; then
    echo "     名字   : ✅ $NM"
  else
    echo "     名字   : ⚠️ **未登记** —— 建议加进 $REG"
  fi
  echo
done
