#!/usr/bin/env bash
# 优化8 验证:流程执行中改/删流程
#
#   1. 执行中删**别的**流程(会 reload 重写脚本表):正在跑的流程照常跑满自己的步数
#   2. 执行中删/改**正在跑的**流程:蓝牙 DEL、网页 delete/save 都明确拒绝;网页恢复出厂也拒绝
#   3. 执行中改别的流程:允许
#   4. 定时循环按名字找流程:循环期间删掉排在它前面的流程,后面几轮跑的仍是它;
#      删掉它本身 ⇒ 循环自动停止
#
# 网页接口通过手机 adb 里的 curl 调(手机开热点,板子连在手机热点上)
# ⚠️ 用例 2 里会调「恢复出厂」验证它被拒绝 —— 跑之前先备份流程分区
#
# 用法: bash tests/ble/test_running_guard.sh [串口日志] [板子IP] [手机adb序列号]
set -u
SERIAL_LOG=${1:-/tmp/serial.log}
BOARD_IP=${2:-10.207.182.250}       # 板子在手机热点里的 IP
PHONE=${3:-R5CW22S4Q6B}             # 手机 adb 序列号
source "$(dirname "$0")/lib.sh"
P=优化8_                            # 测试流程前缀

urlenc() { python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1"; }
# 通过手机调板子网页接口:phone_http <路径(含查询串)> [正文];输出 "HTTP码 响应"
phone_http() {
  local body=${2:-}
  adb -s "$PHONE" shell "curl -s -m 8 -o /data/local/tmp/r.txt -w '%{http_code}' -X POST \
    -H 'Content-Type: text/plain' --data-binary '$body' 'http://$BOARD_IP$1'; echo -n ' '; cat /data/local/tmp/r.txt"
}
idx_of() { flow_index "$1"; }

echo "===== 准备测试流程 ====="
for n in 1 2 3; do save_flow "${P}填充$n" "wait 1" > /dev/null; done
save_flow "${P}跑" "wait 4000\\nwait 4000\\nwait 4000" > /dev/null       # 3 步,约 12 秒
save_flow "${P}大" "$(python3 -c "print('\\\\n'.join(['wait 1']*20))")" > /dev/null   # 20 步
ble_cmd LIST | python3 -c '
import json,sys
for s in json.load(sys.stdin)["scripts"]:
    if s["name"].startswith(sys.argv[1]): print("  ", s["index"], s["name"], s["steps"], "步")' "$P"

# 选一个排在「跑」前面的填充流程当"被删的"(删它会让「跑」的序号前移)
pick_before() {   # pick_before <目标名>
  local t; t=$(idx_of "$1")
  ble_cmd LIST | python3 -c '
import json,sys
t=int(sys.argv[2]); best=None
for s in json.load(sys.stdin)["scripts"]:
    if s["name"].startswith(sys.argv[1]+"填充") and s["index"] < t: best=s["name"]
print(best or "")' "$P" "$t"
}

echo "===== 1~3. 执行「${P}跑」期间的各种改/删 ====="
victim=$(pick_before "${P}跑")
[ -n "$victim" ]; check "有排在「跑」前面的流程可删($victim)" $?
from=$(wc -l < "$SERIAL_LOG")
ble_cmd "RUN $(idx_of "${P}跑")" > /dev/null
# ⚠️ 后面有破坏性请求(恢复出厂):必须确认流程**真的在跑**才继续,否则直接退出
#    (踩过:引擎 bug 导致流程 0 步瞬间结束,"执行中"的拒绝全没生效,恢复出厂真把流程清空了)
started=$(wait_log "$from" 10 "执行 \[.*${P}跑 (3 步)")
echo "  启动: $started"
is_running() { ble_cmd STATUS | python3 -c 'import json,sys;print(json.load(sys.stdin)["run"]["running"])' 2>/dev/null; }
if [ -z "$started" ] || [ "$(is_running)" != True ]; then
  echo "  ❌ 流程没有按预期开始执行(3 步、running=true),中止测试,不发任何破坏性请求"
  exit 1
fi
r=$(ble_cmd "DEL $victim"); echo "  执行中删别的($victim): $r"
echo "$r" | grep -q '"ok":true'; check "执行中可以删别的流程" $?
r=$(ble_cmd "DEL ${P}跑"); echo "  蓝牙删正在跑的: $r"
echo "$r" | grep -q "正在执行"; check "蓝牙 DEL 正在跑的流程被拒绝" $?
r=$(phone_http "/api/delete?name=$(urlenc "${P}跑")"); echo "  网页删正在跑的: $r"
echo "$r" | grep -q "^409 .*正在执行"; check "网页 delete 正在跑的流程被拒绝(409)" $?
r=$(phone_http "/api/save?name=$(urlenc "${P}跑")" "wait 1"); echo "  网页改正在跑的: $r"
echo "$r" | grep -q "正在执行"; check "网页 save 正在跑的流程被拒绝" $?
r=$(phone_http "/api/save?name=$(urlenc "${P}填充3")" "wait 3"); echo "  网页改别的: $r"
echo "$r" | grep -q '^200 '; check "执行中网页可以改别的流程" $?
if [ "$(is_running)" = True ]; then
  r=$(phone_http "/api/system?op=factory"); echo "  恢复出厂: $r"
  echo "$r" | grep -q "^409 "; check "执行中恢复出厂被拒绝(409)" $?
else
  echo "  ❌ 流程已经不在执行,跳过恢复出厂用例(避免真的清空)"; FAIL=$((FAIL + 1))
fi
line=$(wait_run_end "$from" 20); echo "  结果: $line"
echo "$line" | grep -q "执行结束: 成功"; check "「跑」执行成功" $?
done_line=$(wait_log "$from" 1 "\[script\] 完成:"); echo "  引擎: $done_line"
echo "$done_line" | grep -q "完成:3 步"; check "跑满自己的 3 步(脚本表被重写也不受影响)" $?
n=$(ble_cmd LIST | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["scripts"]))')
echo "  执行后流程数: $n"
[ "$n" -ge 9 ]; check "恢复出厂没有生效(流程还在)" $?

echo "===== 4. 定时循环按名字找流程 ====="
save_flow "${P}定时" "wait 100" > /dev/null
victim=$(pick_before "${P}定时")
[ -n "$victim" ]; check "有排在「定时」前面的流程可删($victim)" $?
from=$(wc -l < "$SERIAL_LOG")
r=$(phone_http "/api/schedule?index=$(idx_of "${P}定时")&ms=1500&durMs=90000"); echo "  开启循环: $r"
sleep 4
r=$(ble_cmd "DEL $victim"); echo "  循环中删前面的($victim): $r"
mark=$(wc -l < "$SERIAL_LOG")
sleep 5
names=$(tail -n +"$((mark + 1))" "$SERIAL_LOG" | grep "\[script\] 执行 \[" | sed 's/.*\] \(.*\) (.*/\1/' | sort | uniq -c)
echo "  删除后执行过的流程:"; echo "$names" | sed 's/^/     /'
[ -n "$names" ] && ! echo "$names" | grep -qv "${P}定时"; check "删除后每一轮跑的都是「定时」" $?
for t in 1 2 3 4 5; do   # 赶在两轮之间删(执行中会被拒绝)
  r=$(ble_cmd "DEL ${P}定时"); echo "$r" | grep -q '"ok":true' && break; sleep 0.3
done
echo "  删掉「定时」本身: $r"
line=$(wait_log "$mark" 10 "已不存在"); echo "  $line"
echo "$line" | grep -q "停止循环"; check "流程被删 ⇒ 循环自动停止" $?
phone_http "/api/schedule/stop" > /dev/null

echo "===== 清理 ====="
for nm in $(ble_cmd LIST | python3 -c '
import json,sys
for s in json.load(sys.stdin)["scripts"]:
    if s["name"].startswith(sys.argv[1]): print(s["name"])' "$P"); do
  echo "  DEL $nm: $(ble_cmd "DEL $nm")"
done

finish
