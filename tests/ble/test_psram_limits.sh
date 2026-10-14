#!/usr/bin/env bash
# 优化7 验证:脚本表放 PSRAM + 上限放宽到 32 个 × 128 步
#
# 检查:
#   1. 开机日志:脚本表分配在 PSRAM、内存概况、没有流程因解析失败被跳过
#   2. 128 步的流程能存、能跑满 128 步;129 步明确报错(以前是默默截断)
#   3. 存满 32 个后再新建明确报错;同名覆盖仍允许
#   4. 32 个流程时心跳照常成功(全部上报)
#
# 前提同 test_loop_guard.sh;串口日志要包含本次开机的日志(烧录后再开始记录)
# 用法: bash tests/ble/test_psram_limits.sh [串口日志文件]
set -u
SERIAL_LOG=${1:-/tmp/serial.log}
source "$(dirname "$0")/lib.sh"
PREFIX=优化7_                       # 本测试建的流程都用这个前缀,最后统一删

# 生成 n 行 "wait 1"(用 \n 连接)
waits() { python3 -c "print('\\\\n'.join(['wait 1']*$1))"; }

echo "===== 1. 开机日志 ====="
line=$(grep -m1 "脚本表在 PSRAM" "$SERIAL_LOG"); echo "  $line"
echo "$line" | grep -q "32 个 × 128 步"; check "脚本表在 PSRAM,32 × 128" $?
line=$(grep -m1 "\[boot\] 内存" "$SERIAL_LOG"); echo "  $line"
[ -n "$line" ]; check "有内存概况" $?
skipped=$(grep -c "\[script\] 跳过" "$SERIAL_LOG")
[ "$skipped" = 0 ]; check "开机没有流程被跳过(跳过 $skipped 个)" $?

echo "===== 2. 128 步 / 129 步 ====="
r=$(save_flow "${PREFIX}128步" "$(waits 128)"); echo "  128 步: $r"
echo "$r" | grep -q '"ok":true'; check "128 步可以保存" $?
from=$(wc -l < "$SERIAL_LOG")
res=$(run_flow "${PREFIX}128步" 30)
done_line=$(wait_log "$from" 1 "\[script\] 完成:"); echo "  引擎: $done_line"
echo "$done_line" | grep -q "完成:128 步"; check "跑满 128 步" $?
echo "$res" | grep -q "执行结束: 成功"; check "执行成功" $?
r=$(save_flow "${PREFIX}129步" "$(waits 129)"); echo "  129 步: $r"
echo "$r" | grep -q "步骤太多"; check "129 步明确报'步骤太多'" $?

echo "===== 3. 存满 32 个 ====="
n=$(ble_cmd LIST | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["scripts"]))')
echo "  当前 $n 个流程,补到 32 个..."
i=0
while [ "$n" -lt 32 ]; do
  i=$((i + 1))
  r=$(save_flow "${PREFIX}填充$(printf %02d $i)" "wait 1")
  echo "$r" | grep -q '"ok":true' || { echo "  第 $i 个保存失败: $r"; break; }
  n=$((n + 1))
done
n=$(ble_cmd LIST | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["scripts"]))')
[ "$n" = 32 ]; check "LIST 里有 32 个流程(实际 $n)" $?
r=$(ble_cmd "SAVE -1\\n${PREFIX}第33个\\nwait 1"); echo "  第 33 个: $r"
echo "$r" | grep -q "已达上限 32"; check "第 33 个明确报'已达上限 32'" $?
r=$(save_flow "${PREFIX}填充01" "wait 2"); echo "  满了覆盖同名: $r"
echo "$r" | grep -q '"ok":true'; check "满了仍可覆盖同名流程" $?

echo "===== 4. 32 个流程时的心跳(最多等 150 秒)====="
from=$(wc -l < "$SERIAL_LOG")
line=$(wait_log "$from" 150 "心跳成功\|心跳失败"); echo "  $line"
echo "$line" | grep -q "心跳成功.*含流程列表"; check "心跳成功并带上 32 个流程" $?
heap=$(ble_cmd STATUS | python3 -c 'import json,sys;print(json.load(sys.stdin)["heap"])' 2>/dev/null)
echo "  当前内部 RAM 空闲: $heap 字节"

echo "===== 清理 ====="
for nm in $(ble_cmd LIST | python3 -c '
import json,sys
for s in json.load(sys.stdin)["scripts"]:
    if s["name"].startswith(sys.argv[1]): print(s["name"])' "$PREFIX"); do
  echo "  DEL $nm: $(ble_cmd "DEL $nm")"
done

finish
