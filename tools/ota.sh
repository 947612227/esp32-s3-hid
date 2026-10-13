#!/usr/bin/env bash
# 一键 OTA 升级:编译 → HTTP 上传 → 等板子回来
#
# 用法:  tools/ota.sh [板子IP]
# 默认:  192.168.5.37
#
# 前提:板子固件含 /api/ota 接口(2026-10 之后的版本),且和电脑同一网段。
# 注意:OTA **不会**动脚本文件系统,流程不会丢。
set -uo pipefail

IP=${TP_BOARD_IP:-192.168.4.1}
ENV=ble
BIN=".pio/build/$ENV/firmware.bin"

cd "$(dirname "$0")/.." || exit 1

echo "== 1/4 探活 =="
if ! curl -s -m 5 "http://$IP/api/status" >/dev/null; then
  echo "  ✗ 连不上 http://$IP —— 确认板子在线、和电脑同网段"
  exit 1
fi
echo "  ✓ 板子在线"

echo "== 2/4 编译 =="
pio run -e "$ENV" 2>&1 | tail -3
if [ ! -f "$BIN" ]; then echo "  ✗ 没生成 $BIN"; exit 1; fi
echo "  ✓ 固件 $(stat -f%z "$BIN") 字节"

echo "== 3/4 上传(OTA)=="
RESP=$(curl -s -m 120 -X POST "http://$IP/api/ota" -F "firmware=@$BIN" --max-time 120)
echo "  $RESP"

echo "== 4/4 等重启 =="
for i in $(seq 1 30); do
  sleep 3
  if curl -s -m 4 "http://$IP/api/status" >/dev/null 2>&1; then
    echo "  ✓ 第 $((i*3)) 秒回来了"
    curl -s -m 5 "http://$IP/api/status" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f\"  板子: {d['mode']} {d['ip']} · HID={'就绪' if d['hid'] else '未连接'} · {d['scriptCount']} 个流程\")" 2>/dev/null
    exit 0
  fi
done
echo "  ✗ 超时(90 秒没回来)—— 可能需要用 USB 重新烧录"
exit 1
