# 蓝牙真机测试公共函数(被 tests/ble/test_*.sh source)
#
# 依赖:
#   APP         蓝牙测试客户端(tests/ble/ble_ctrl_rtt.swift 编出的 .app)
#   SERIAL_LOG  板子串口日志文件(持续写入)

APP=${APP:-/tmp/BleRtt.app}
OUT=${OUT:-/tmp/ble_test_cmd.out}   # 单次命令的输出
FAIL=0                              # 失败用例数

# 通过蓝牙控制通道发一条命令,打印回包正文(\n 会被客户端换成换行)
ble_cmd() {
  rm -f "$OUT"
  open -n -W --stdout "$OUT" --stderr "$OUT" "$APP" --args 1 "cmd=$1"
  sed -n 's/^回包内容://p' "$OUT"
}

# 按名字查流程序号
flow_index() {
  ble_cmd "LIST" | python3 -c '
import json,sys
name=sys.argv[1]
for s in json.load(sys.stdin)["scripts"]:
    if s["name"]==name: print(s["index"]); break' "$1"
}

# 等串口日志第 $1 行之后出现匹配 $3 的行,最多 $2 秒;打印那一行
wait_log() {
  local from=$1 limit=$2 pat=$3 i line
  for ((i = 0; i < limit * 10; i++)); do
    line=$(tail -n +"$((from + 1))" "$SERIAL_LOG" | grep -m1 -- "$pat")
    if [ -n "$line" ]; then echo "$line"; return 0; fi
    sleep 0.1
  done
  return 1
}
# 等流程跑完(net 层的"执行结束",不是 LED 的那条)
wait_run_end() { wait_log "$1" "$2" "\[net\] 执行结束"; }

check() {   # check <描述> <上一条命令的退出码>
  if [ "$2" = 0 ]; then echo "  ✅ $1"; else echo "  ❌ $1"; FAIL=$((FAIL + 1)); fi
}

# 保存流程;接受了就等它真正落盘(否则紧接着的 LIST 拿到的序号是旧的)
save_flow() {   # save_flow <名字> <正文(\n 分行)>
  local from r
  from=$(wc -l < "$SERIAL_LOG")
  r=$(ble_cmd "SAVE -1\\n$1\\n$2")
  echo "$r"
  if echo "$r" | grep -q '"ok":true'; then
    wait_log "$from" 30 "异步落盘完成:$1" > /dev/null || echo "  ⚠️ 30 秒内没落盘"
  fi
}

now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

# 跑流程直到结束,输出"<结束行>|<耗时ms>"
run_flow() {    # run_flow <名字> <最多等几秒>
  local idx from t0 t1 line
  idx=$(flow_index "$1")
  [ -z "$idx" ] && { echo "找不到流程|0"; return; }
  from=$(wc -l < "$SERIAL_LOG")
  t0=$(now_ms)
  ble_cmd "RUN $idx" > /dev/null
  line=$(wait_run_end "$from" "$2")
  t1=$(now_ms)
  echo "${line}|$((t1 - t0))"
}

finish() {
  echo
  if [ "$FAIL" = 0 ]; then echo "全部通过"; else echo "失败 $FAIL 项"; exit 1; fi
}
