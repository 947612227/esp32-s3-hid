#!/usr/bin/env bash
# wait 精度测试:流程里的等待实际花了多久(引擎自己报的"完成:N 步,耗时 X ms")
#
#   A. 128 × wait 1     名义 128ms   (日志 ~3.6KB,不会塞满串口缓冲)
#   B. 20 × wait 50     名义 1000ms
#   C. wait 1000        名义 1000ms
#   D. 600 × wait 3     名义 1800ms  (每轮 3 行日志,串口 115200 下每行 ~1ms,只记录)
#
# 修复前(idleWait 固定 delay(5)):A ≈ 800ms
#
# 用法: bash tests/ble/test_wait_accuracy.sh [串口日志]
set -u
SERIAL_LOG=${1:-/tmp/serial.log}
source "$(dirname "$0")/lib.sh"
P=精度测试_

lines() { python3 -c "import sys;print('\\\\n'.join([sys.argv[1]]*int(sys.argv[2])))" "$1" "$2"; }

# 跑一个流程,打印引擎耗时(毫秒)
engine_ms() {   # engine_ms <名字>
  local from line
  from=$(wc -l < "$SERIAL_LOG")
  run_flow "$1" 60 > /dev/null
  line=$(wait_log "$from" 2 "\[script\] 完成:")
  echo "$line" | sed -n 's/.*耗时 \([0-9]*\) ms.*/\1/p'
}
# 判断 实际 ≤ 上限
within() { [ -n "$1" ] && [ "$1" -le "$2" ]; }

save_flow "${P}A" "$(lines 'wait 1' 128)" > /dev/null
save_flow "${P}B" "$(lines 'wait 50' 20)" > /dev/null
save_flow "${P}C" "wait 1000" > /dev/null
save_flow "${P}D" "repeat 600\\nwait 3\\nendloop" > /dev/null

a=$(engine_ms "${P}A"); echo "  A 128 × wait 1  : ${a} ms(名义 128)"
within "$a" 300;  check "A ≤ 300ms" $?
b=$(engine_ms "${P}B"); echo "  B 20 × wait 50  : ${b} ms(名义 1000)"
within "$b" 1060; check "B ≤ 1060ms(+6%)" $?
c=$(engine_ms "${P}C"); echo "  C wait 1000     : ${c} ms(名义 1000)"
within "$c" 1010; check "C ≤ 1010ms" $?
d=$(engine_ms "${P}D"); echo "  D 600 × wait 3  : ${d} ms(名义 1800,受串口日志速率限制,只记录不判定)"

for n in A B C D; do ble_cmd "DEL ${P}$n" > /dev/null; done
finish
