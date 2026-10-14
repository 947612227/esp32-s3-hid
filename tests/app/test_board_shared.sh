#!/usr/bin/env bash
# 优化12 验证(App 真机):Board 共用实例 + 请求合并/缓存作废 + null 回调 + 拆出来的手势/日志页签
#
#   1. 6 个 status 同时发:都拿到结果,但真正走网络的只有 1~3 次(在途合并 + 短缓存,
#      而且和界面自己的轮询共用同一个实例)
#   2. 写操作后作废读缓存:App 内连着 读日志 → 清空 → 再读,第二次读必须真走网络、读到清空后的日志
#   3. null 回调的请求也要发出去:scheduleStop(null)(老代码根本不发)
#   4. 切到 手势 / 日志 / 状态 页签不崩
#
# 原理:通过 DebugReceiver 广播触发真实请求;Board 每次真正走网络都会打一行
#       TouchLink 日志(收到 "← BLE"/"← HTTP",失败 "✗ HTTP"),数这些行就知道发了几次。
# 前提:手机 USB 连着电脑、已装新版 App、App 能连上板子(蓝牙或 WiFi 都行)。
# ⚠️ 会清空板子运行日志、停掉板子上的定时;不动流程。
#
# 用法: bash tests/app/test_board_shared.sh [手机adb序列号]   (不填 = 第一台 adb 设备)
set -u
PKG=com.dsh.touchpad
PHONE=${1:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}   # 手机 adb 序列号
[ -n "$PHONE" ] || { echo "❌ 没找到 adb 设备"; exit 2; }
A() { adb -s "$PHONE" "$@"; }

FAILS=0                                   # 失败项数
ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*"; FAILS=$((FAILS + 1)); }

# 发一条调试广播:dbg <op> [要切到的页签名]   (op 见 DebugReceiver)
dbg() {
  if [ $# -ge 2 ]; then
    A shell am broadcast -a $PKG.DEBUG -n $PKG/.DebugReceiver --es op "$1" --es tab "$2" >/dev/null
  else
    A shell am broadcast -a $PKG.DEBUG -n $PKG/.DebugReceiver --es op "$1" >/dev/null
  fi
}
app_pid() { A shell pidof $PKG | tr -d '\r'; }
# 本 App 进程的 TouchLink / TouchPadDebug 日志
app_log() { A logcat -d -v brief TouchLink:I TouchPadDebug:I '*:S' --pid="$(app_pid)"; }
# 某路径真正走了几次网络(每次往返恰好一行 "←" 或 "✗")
net_count() { app_log | grep -cE "[←✗] (BLE|HTTP) +$1( |$)"; }

echo "== 准备:冷启动 App(设备 $PHONE)=="
A shell am force-stop $PKG
A shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
[ -n "$(app_pid)" ] || { echo "❌ App 没起来"; exit 2; }

echo "== 1. 共用实例 + 在途合并/短缓存:6 个 status 同时发 =="
A logcat -c
pids=()
for i in 1 2 3 4 5 6; do dbg status & pids+=($!); done
wait "${pids[@]}"
sleep 4
results=$(app_log | grep -c "RESULT ok=true")     # 拿到结果的个数
net=$(net_count /api/status)                       # 真正走网络的次数
echo "  收到结果 $results 个,真正走网络 $net 次"
[ "$results" -eq 6 ] && ok "6 个请求都拿到结果" || fail "应收到 6 个结果,实际 $results"
# 6 个几乎同时到:应合并成 1 次;界面自己每 5 秒也拉一次 status,最多再多 1~2 次
[ "$net" -ge 1 ] && [ "$net" -le 3 ] && ok "被合并(≤3 次网络)" || fail "没合并:走了 $net 次网络"

echo "== 2. 写操作后作废读缓存:App 内连着做 读日志 → 清空 → 再读(都在 1.5 秒缓存期内)=="
A logcat -c
dbg logs:clear; sleep 4
[ "$(net_count /api/logs/clear)" -eq 1 ] && ok "清空请求已发出" || fail "清空请求没发出"
n=$(net_count /api/logs)
# 悬浮窗开着时界面也会轮询日志,可能多出几次 ⇒ 只要求 ≥2
[ "$n" -ge 2 ] && ok "两次读日志都走了网络(清空后没用旧缓存)" || fail "读日志走网络 $n 次(应 ≥2;1 次 = 清空后用了旧缓存)"
if app_log | grep "RESULT ok=true" | tail -1 | grep -q "日志已清空"; then
  ok "读到的是清空后的新日志"
else
  fail "读到的不是清空后的日志"
fi

echo "== 3. null 回调的请求也要发:scheduleStop(null) =="
A logcat -c
dbg schedstop:null; sleep 4
[ "$(net_count /api/schedule/stop)" -ge 1 ] && ok "schedule/stop 已发出" || fail "null 回调的请求没发出(老 bug)"

echo "== 4. 拆出来的页签:切到 手势 / 日志 / 状态,不崩 =="
A logcat -c
for t in 手势 日志 状态; do dbg status "$t"; sleep 3; done
switched=$(app_log | grep -c "SWITCHED TAB")
[ "$switched" -eq 3 ] && ok "3 次切页签都执行了" || fail "切页签只执行了 $switched 次"
if A logcat -d | grep -q "FATAL EXCEPTION"; then fail "App 崩溃了"; else ok "无崩溃"; fi
[ -n "$(app_pid)" ] && ok "App 进程还在" || fail "App 进程没了"

echo
if [ "$FAILS" -eq 0 ]; then echo "全部通过"; else echo "失败 $FAILS 项"; exit 1; fi
