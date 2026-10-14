#!/usr/bin/env bash
# 优化5 验证:repeat 次数上限 / 死循环检测 / 无等待循环里的「停止」
#
# 前提:
#   1. 已用 tests/ble/ble_ctrl_rtt.swift 编好 /tmp/BleRtt.app(见文件头说明)
#   2. 板子串口日志在持续写入 $1(默认 /tmp/serial.log)
#   3. 手机已作为 HID 主机连上板子(否则 RUN 会被跳过:"HID 未就绪")
#
# 用法: bash tests/ble/test_loop_guard.sh [串口日志文件]
set -u
SERIAL_LOG=${1:-/tmp/serial.log}   # 串口日志文件
source "$(dirname "$0")/lib.sh"

echo "===== 1. 解析器:repeat 次数范围 ====="
r=$(save_flow "优化5_超限" "repeat 70000\\nwait 1\\nendloop")
echo "  回包: $r"
echo "$r" | grep -q "1~65535"; check "repeat 70000 被拒绝(提示 1~65535)" $?

echo "===== 2. repeat 600(>255,总步数 >1024)能完整跑完 ====="
# 每轮 wait 3ms:600 轮 ≈ 1.8 秒;旧固件 uint8 会截成 88 轮(≈0.26 秒),且总步数 >1024 会被误判死循环
r=$(save_flow "优化5_长循环" "repeat 600\\nwait 3\\nendloop")
echo "  回包: $r"
from=$(wc -l < "$SERIAL_LOG")
res=$(run_flow "优化5_长循环" 30)
echo "  结果: ${res%|*}  (蓝牙往返总耗时 ${res##*|} ms)"
echo "$res" | grep -q "执行结束: 成功"; check "执行成功(没有被误判死循环)" $?
# 引擎自己报的步数:每轮 repeat/wait/endloop 3 步 × 600 轮 = 1800
done_line=$(wait_log "$from" 1 "\[script\] 完成:")
echo "  引擎: $done_line"
echo "$done_line" | grep -q "完成:1800 步"; check "执行 1800 步(说明真的跑满 600 轮)" $?
el=$(ble_cmd "STATUS" | python3 -c 'import json,sys;print(json.load(sys.stdin)["run"]["elapsedMs"])' 2>/dev/null)
echo "  板子侧耗时 elapsedMs=$el"
[ "${el:-0}" -ge 1800 ] 2>/dev/null; check "耗时 ≥1800ms(600 × wait 3)" $?

echo "===== 3. 空转死循环被拦下 ====="
r=$(save_flow "优化5_空转" "repeat 65535\\nendloop")
echo "  回包: $r"
res=$(run_flow "优化5_空转" 60)
echo "  结果: ${res%|*}  (总耗时 ${res##*|} ms)"
echo "$res" | grep -q "疑似死循环"; check "报'疑似死循环'" $?
# 万一没拦住,停掉它,别影响后面的用例
echo "$res" | grep -q "执行结束" || ble_cmd STOP > /dev/null

echo "===== 4. 只有点击、没有 wait 的循环也能被「停止」 ====="
# 点状态栏(y=1%),单击无副作用;5000 次 × 约 25ms ≈ 2 分钟,足够在中途停
r=$(save_flow "优化5_无等待点击" "repeat 5000\\nT 0.50 0.01 20\\nendloop")
echo "  回包: $r"
idx=$(flow_index "优化5_无等待点击")
from=$(wc -l < "$SERIAL_LOG")
ble_cmd "RUN $idx" > /dev/null
sleep 3
t0=$(now_ms)
echo "  STOP 回包: $(ble_cmd STOP)"
line=$(wait_run_end "$from" 15)
t1=$(now_ms)
echo "  结果: $line  (发 STOP 到结束 $((t1 - t0)) ms,含蓝牙连接)"
echo "$line" | grep -q "被用户中止"; check "被用户中止" $?
started=$(wait_log "$from" 1 "\[script\] 执行 \[")
echo "  实际启动: $started"
echo "$started" | grep -q "优化5_无等待点击"; check "停掉的是无等待点击循环本身" $?

echo "===== 清理测试流程 ====="
for n in 优化5_长循环 优化5_空转 优化5_无等待点击; do
  echo "  DEL $n: $(ble_cmd "DEL $n")"
done

finish
