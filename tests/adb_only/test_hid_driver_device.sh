#!/usr/bin/env bash
# 优化13 真机验证:adb-only 的 --driver hid(找控件用 adb,动作交给板子 HID 触摸)
#
#   1. 在触摸注入板 App 里按文字点「日志」「状态」页签 → 界面真的切过去了(坐标换算对)
#   2. 状态页上滑 → 页面滚动;再滑回去
#   3. 按键:最近任务 → 返回 → 回到 App;HOME → 桌面
#   4. 板子日志里有对应的「Web 手势 / Web 控制」记录(证明是板子注入的,不是 adb input)
#
# 前提:手机 USB 连电脑;板子 HID 已连手机;板子在手机热点上(脚本用 --relay 经手机 curl 转发)
# ⚠️ 会清空板子运行日志
# 用法: bash tests/adb_only/test_hid_driver_device.sh [板子IP] [手机adb序列号]
set -u
BOARD=${1:-10.207.182.250}          # 板子在手机热点里的 IP
PHONE=${2:-R5CW22S4Q6B}             # 手机 adb 序列号
PKG=com.dsh.touchpad
CLICK="$(cd "$(dirname "$0")/../../adb-only" && pwd)/click.py"
A() { adb -s "$PHONE" "$@"; }

FAILS=0                                   # 失败项数
ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*"; FAILS=$((FAILS + 1)); }

# 经板子注入:hid <click.py 子命令...>
hid() { python3 "$CLICK" -s "$PHONE" -q --driver hid --board "$BOARD" --relay "$@"; }
# 当前界面所有文字(一行一个,带 bounds)
ui_texts() {
  A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  A shell cat /sdcard/ui.xml | python3 -c '
import re,sys
for m in re.finditer(r"text=\"([^\"]+)\"[^>]*bounds=\"([^\"]+)\"", sys.stdin.read()): print(m.group(1), m.group(2))'
}
focus() { A shell dumpsys window | grep -m1 mCurrentFocus; }
board_log() { A shell "curl -s -m 8 http://$BOARD/api/logs"; }

echo "== 准备:打开 App 到状态页,清空板子日志(第 4 步只认本次的记录)=="
A shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5
hid tap 状态 >/dev/null; sleep 2
A shell "curl -s -m 8 -X POST http://$BOARD/api/logs/clear" >/dev/null

echo "== 1. 按文字点页签 =="
hid tap 日志 && sleep 2
ui_texts | grep -q "^运行日志" && ok "点「日志」→ 切到日志页" || fail "没切到日志页"
hid tap 状态 && sleep 2
ui_texts | grep -q "^连接方式 " && ok "点「状态」→ 切回状态页" || fail "没切回状态页"

echo "== 2. 滑动 =="
before=$(ui_texts | grep "^连接方式 ")
hid swipe 50%,80% 50%,40% --ms 400; sleep 2
after=$(ui_texts | grep "^连接方式 ")
[ "$before" != "$after" ] && ok "上滑后页面滚动了(${after:-已滚出屏幕})" || fail "上滑后页面没动"
hid swipe 50%,40% 50%,85% --ms 300; sleep 1

echo "== 3. 按键 =="
hid key APP_SWITCH; sleep 2
focus | grep -q RecentsActivity && ok "APP_SWITCH → 最近任务" || fail "没打开最近任务:$(focus)"
hid key BACK; sleep 2
focus | grep -q "$PKG" && ok "BACK → 回到 App" || fail "BACK 后不在 App:$(focus)"
hid key HOME; sleep 2
focus | grep -q LauncherActivity && ok "HOME → 桌面" || fail "HOME 后不在桌面:$(focus)"

echo "== 4. 板子日志有记录(确实是板子注入)=="
log=$(board_log)
for want in "已点击" "已滑动" "已打开最近任务" "已返回" "已回桌面"; do
  echo "$log" | grep -q "$want" && ok "板子日志有「${want}」" || fail "板子日志没有「${want}」"
done

echo "== 收尾:回到 App =="
A shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo
if [ "$FAILS" -eq 0 ]; then echo "全部通过"; else echo "失败 $FAILS 项"; exit 1; fi
