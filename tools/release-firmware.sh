#!/usr/bin/env bash
# ============================================================================
#  发布固件到服务器 —— 让别人能从网页直接下载烧录
#
#  做三件事:
#    ① 编译三个环境(ble / usb / usb_touchtest)
#    ② 合并成单文件 full.bin(新板子只需烧一个文件,不用记 4 个偏移)
#    ③ 打包固件 + 烧录脚本 + 说明,上传到服务器 /download/
#
#  用法:
#    ./tools/release-firmware.sh
#    ./tools/release-firmware.sh -n "修了验证码处理"     # 带发布说明
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER="${TP_SERVER_SSH:-root@120.55.183.206}"
REMOTE_DIR="/root/touchpad-server/download"
BOOT_APP0="$HOME/.platformio/packages/framework-arduinoespressif32/tools/partitions/boot_app0.bin"

NOTES=""
while getopts "n:h" opt; do
  case $opt in
    n) NOTES="$OPTARG" ;;
    h) sed -n '2,14p' "$0"; exit 0 ;;
    *) exit 1 ;;
  esac
done

[ -f "$BOOT_APP0" ] || { echo "❌ 找不到 boot_app0.bin,先编译一次工程"; exit 1; }

echo "== 1/4 编译三个环境 =="
pio run -e ble -e usb -e usb_touchtest 2>&1 | grep -E "SUCCESS|FAILED" | sed 's/^/   /'

echo
echo "== 2/4 合并成单文件固件 =="
mkdir -p dist
for e in ble usb usb_touchtest; do
  # ⚠️ 用 --flash_mode keep / --flash_freq keep:
  #    bootloader 自带 hash 校验和,强行改 flash 参数会报 warning。
  esptool.py --chip esp32s3 merge_bin -o "dist/touchpad-$e-full.bin" \
    --flash_mode keep --flash_freq keep --flash_size 8MB \
    0x0     ".pio/build/$e/bootloader.bin" \
    0x8000  ".pio/build/$e/partitions.bin" \
    0xe000  "$BOOT_APP0" \
    0x10000 ".pio/build/$e/firmware.bin" >/dev/null 2>&1
  printf "   %-34s %9d 字节\n" "touchpad-$e-full.bin" "$(stat -f%z "dist/touchpad-$e-full.bin" 2>/dev/null || stat -c%s "dist/touchpad-$e-full.bin")"
done

echo
echo "== 3/4 生成版本信息 =="
VERSION=$(grep -oE 'FW_VERSION=\\"[^\\"]+' platformio.ini | head -1 | sed 's/.*"//' || echo "1.0.0")
cat > dist/version.json <<JSON
{
  "version": "${VERSION:-1.0.0}",
  "builtAt": "$(date '+%Y-%m-%d %H:%M:%S')",
  "notes": $(python3 -c "import json,sys;print(json.dumps(sys.argv[1]))" "$NOTES"),
  "files": [
    {"name": "touchpad-ble-full.bin", "label": "蓝牙版(推荐)", "size": $(stat -f%z dist/touchpad-ble-full.bin 2>/dev/null || stat -c%s dist/touchpad-ble-full.bin)},
    {"name": "touchpad-usb-full.bin", "label": "USB 版", "size": $(stat -f%z dist/touchpad-usb-full.bin 2>/dev/null || stat -c%s dist/touchpad-usb-full.bin)},
    {"name": "touchpad-usb_touchtest-full.bin", "label": "自检版(开机自动点四角验证坐标)", "size": $(stat -f%z dist/touchpad-usb_touchtest-full.bin 2>/dev/null || stat -c%s dist/touchpad-usb_touchtest-full.bin)}
  ],
  "scripts": [
    {"name": "flash-windows.bat", "label": "Windows 一键烧录"},
    {"name": "flash-mac-linux.sh", "label": "Mac / Linux 一键烧录"},
    {"name": "README.txt", "label": "烧录说明"}
  ]
}
JSON
cat dist/version.json | sed 's/^/   /'

echo
echo "== 4/4 上传到服务器 =="
export SSHPASS="${SSHPASS:-yixia1112}"
sshpass -e ssh -o StrictHostKeyChecking=no "$SERVER" "mkdir -p $REMOTE_DIR" 2>/dev/null
for f in dist/*.bin dist/*.bat dist/*.sh dist/README.txt dist/version.json dist/index.html; do
  sshpass -e scp -o StrictHostKeyChecking=no -q "$f" "$SERVER:$REMOTE_DIR/" && echo "   ↑ $(basename "$f")"
done
sshpass -e ssh -o StrictHostKeyChecking=no "$SERVER" \
  "chmod 644 $REMOTE_DIR/*; ls -la $REMOTE_DIR | tail -9" 2>&1 | sed 's/^/   /'

echo
echo "   ✅ 下载页: http://120.55.183.206/download/"
