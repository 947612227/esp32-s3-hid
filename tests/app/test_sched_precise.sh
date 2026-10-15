#!/usr/bin/env bash
# 定时到点精度自测(真机)
#
# 验证:
#   ① 定时到点后**立即**触发:日志里的「晚了 X ms」≤ 允许值(以前 1 秒节拍最多晚将近 1 秒)
#   ② RUN 是第一个发出去的请求(在写日志之前)
#   ③ 流程带 waitfor/ifpage 而页面识别没开时,有「页面识别没开」的警告
#
# 安全:测试流程只有 wait / 超时 100ms 的 waitfor,**不点屏幕**;
#      目标 App 是不存在的包名,到点什么都不拉起;不动用户已开启的定时;测完删干净。
#
# 用法:bash tests/app/test_sched_precise.sh [手机序列号] [板子IP]
set -u
S=${1:-R5CW22S4Q6B}            # 手机 adb 序列号
B=${2:-10.207.182.250}         # 板子 IP(手机通过 WiFi 访问)
PKG=com.dsh.touchpad
ALLOW_LATE=${ALLOW_LATE_MS:-60}  # 允许的最大触发延迟(毫秒)
ROUNDS=${ROUNDS:-3}             # 测几轮
A() { adb -s "$S" "$@"; }
FAILS=0                        # 失败项数
ok()   { echo "  ✅ $*"; }
bad()  { echo "  ❌ $*"; FAILS=$((FAILS+1)); }
urlenc() { python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1"; }

# 在板子上存一个流程(经手机 curl,板子只在手机能到的网里)
板子存() { A shell "curl -s -m 8 -X POST -H 'Content-Type: text/plain; charset=utf-8' --data-binary '$2' 'http://$B/api/save?name=$(urlenc "$1")'"; echo; }
板子删() { A shell "curl -s -m 8 -X POST 'http://$B/api/delete?name=$(urlenc "$1")'" >/dev/null; }
广播()   { A shell am broadcast -a $PKG.DEBUG -n $PKG/.DebugReceiver "$@" >/dev/null; }

NAME_A="定时自测A"     # 不用页面识别
NAME_B="定时自测B"     # 带 waitfor

清理() {
  广播 --es op schedclear --es name "${NAME_A}"
  广播 --es op schedclear --es name "${NAME_B}"
  板子删 "${NAME_A}"; 板子删 "${NAME_B}"
}
trap 清理 EXIT

echo "== 0. 准备:板子上建两个不点屏幕的测试流程 =="
板子存 "${NAME_A}" "wait 1 定时自测 不点屏幕"
板子存 "${NAME_B}" "waitfor 自测不存在的页面 100
wait 1 定时自测 不点屏幕"
A shell am start -n $PKG/.MainActivity >/dev/null 2>&1
# App 启动时会把服务器上的流程逐个写进板子,整条蓝牙队列被 SAVE 占着 ⇒ 等它安静下来(最多 90 秒)
echo "  等 App 的启动同步写完…"
for _ in $(seq 1 30); do
  A logcat -c; sleep 5
  A logcat -d -s TouchLink:I | grep -q "/api/save" || break
done

PAGE_ON=0                      # 页面识别(无障碍)开没开
A shell dumpsys accessibility | grep -q "Enabled services:{[^}]*com.dsh.touchpad" && PAGE_ON=1
echo "  页面识别(无障碍)当前: $([ ${PAGE_ON} = 1 ] && echo 开 || echo 关)"

# 跑一轮:name 延时ms → 输出「晚了 X ms」和 RUN 与触发的先后
跑一轮() {
  local name=$1 ms=$2
  A logcat -c
  广播 --es op schedtest --es name "$name" --el ms "$ms"
  sleep $(( ms / 1000 + 4 ))
  A logcat -d -v time -s TouchPad:I TouchLink:I > /tmp/sched_round.log
}

echo "== 1. 到点精度(${ROUNDS} 轮,每轮 6 秒倒计时)=="
for i in $(seq 1 "${ROUNDS}"); do
  跑一轮 "${NAME_A}" 6000
  line=$(grep "定时触发: ${NAME_A}" /tmp/sched_round.log | tail -1)
  late=$(echo "$line" | sed -n 's/.*晚了 \(-\{0,1\}[0-9]*\) ms.*/\1/p')
  if [ -z "${late}" ]; then
    bad "第 ${i} 轮:没触发(日志里没有「定时触发: ${NAME_A}」)"
    grep -E "定时" /tmp/sched_round.log | sed 's/^/      /' | tail -4
    continue
  fi
  if [ "${late}" -le "${ALLOW_LATE}" ]; then ok "第 ${i} 轮:晚了 ${late} ms(≤ ${ALLOW_LATE})"; else bad "第 ${i} 轮:晚了 ${late} ms(> ${ALLOW_LATE})"; fi
  # RUN 要在「定时触发」日志之前发出
  runL=$(grep -n "→ .*/api/run" /tmp/sched_round.log | head -1 | cut -d: -f1)
  logL=$(grep -n "定时触发: ${NAME_A}" /tmp/sched_round.log | head -1 | cut -d: -f1)
  if [ -n "${runL}" ] && [ "${runL}" -lt "${logL}" ]; then ok "第 ${i} 轮:RUN 先发出,再写日志"; else bad "第 ${i} 轮:RUN 不是先发的(RUN 行=${runL} 日志行=${logL})"; fi
  grep -q "页面识别没开" /tmp/sched_round.log && bad "第 ${i} 轮:不带 waitfor 的流程不该有页面识别警告"
  sleep 2
done

echo "== 2. 页面识别提醒 =="
# 倒计时不足 5 分钟时「提前启动」那步会立刻执行并提醒一次,到点再提醒一次;日志里出现即可
跑一轮 "${NAME_B}" 6000
grep -q "定时触发: ${NAME_B}" /tmp/sched_round.log && ok "带 waitfor 的流程按时触发" || bad "带 waitfor 的流程没触发"
if [ ${PAGE_ON} = 0 ]; then
  grep -q "页面识别没开" /tmp/sched_round.log && ok "页面识别关着 → 有警告" || bad "页面识别关着却没有警告"
else
  grep -q "页面识别没开" /tmp/sched_round.log && bad "页面识别开着却报了警告" || ok "页面识别开着 → 不警告"
fi

echo
[ ${FAILS} = 0 ] && echo "全部通过 ✅" || echo "失败 ${FAILS} 项 ❌"
exit ${FAILS}
