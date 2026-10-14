#!/usr/bin/env bash
# 一键检查(不需要板子和手机):改完代码、提交前跑一遍
#
#   1. 流程解析器单元测试(tests/native,电脑上编译固件同一份 script_parser.cpp)
#   2. 固件编译:ble、usb 两个环境
#   3. adb-only HidDriver 单元测试
#   4. Android App 编译
#   5. 服务端 JS 语法检查(node --check)
#   6. 所有 shell 脚本语法检查(bash -n)
#
# 缺工具的项(没装 pio / Android SDK / node)标"跳过",不算失败。
# 要板子/手机的真机用例不在这里跑,结尾会列出来。
#
# 用法: bash scripts/check.sh            全部
#       bash scripts/check.sh --no-fw    跳过固件编译(最慢的一项)
set -u
cd "$(dirname "$0")/.."

NO_FW=0
[ "${1:-}" = "--no-fw" ] && NO_FW=1

FAILS=0                   # 失败项数
LOG=$(mktemp -t check.XXXXXX)
trap 'rm -f "$LOG"' EXIT

# 跑一项:step <名称> <命令...>;失败时打印该项输出的最后 30 行
step() {
  local name=$1; shift
  printf '▶ %s … ' "$name"
  if "$@" >"$LOG" 2>&1; then
    echo "✅"
  else
    echo "❌"
    tail -30 "$LOG" | sed 's/^/    /'
    FAILS=$((FAILS + 1))
  fi
}
skip() { printf '▶ %s … 跳过(%s)\n' "$1" "$2"; }

# 1. 解析器单元测试
if command -v clang++ >/dev/null || command -v g++ >/dev/null; then
  step "流程解析器单元测试" bash tests/native/run.sh
else
  skip "流程解析器单元测试" "没有 C++ 编译器"
fi

# 2. 固件编译
if [ $NO_FW -eq 1 ]; then
  skip "固件编译 ble / usb" "--no-fw"
elif command -v pio >/dev/null; then
  step "固件编译 ble" pio run -e ble
  step "固件编译 usb" pio run -e usb
else
  skip "固件编译 ble / usb" "没有 pio"
fi

# 3. HidDriver 单元测试(adb-only 用了 3.7+ 语法)
if python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 7) else 1)' 2>/dev/null; then
  step "adb-only HidDriver 单元测试" python3 tests/adb_only/test_hid_driver.py
else
  skip "adb-only HidDriver 单元测试" "需要 Python 3.7+"
fi

# 4. App 编译(build.sh 自己找 SDK)
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/app/android_sdk}}"
if [ -d "$SDK" ]; then
  step "Android App 编译" bash android-app/build.sh
else
  skip "Android App 编译" "没有 Android SDK"
fi

# 5. 服务端语法
if command -v node >/dev/null; then
  js_check() {
    local f
    for f in server/index.js server/src/*.js server/src/routes/*.js \
             server/sql/migrations/*.js server/tools/*.js tests/server/*.js; do
      [ -f "$f" ] || continue
      node --check "$f" || { echo "语法错误:$f"; return 1; }
    done
  }
  step "服务端 JS 语法" js_check
else
  skip "服务端 JS 语法" "没有 node"
fi

# 6. shell 脚本语法
sh_check() {
  local f
  for f in scripts/*.sh tests/*/*.sh android-app/*.sh; do
    [ -f "$f" ] || continue
    bash -n "$f" || { echo "语法错误:$f"; return 1; }
  done
}
step "shell 脚本语法" sh_check

echo
echo "真机用例(要板子/手机,需要时单独跑):"
echo "  tests/ble/*.sh                         固件行为(蓝牙控制通道 + 串口日志)"
echo "  tests/app/test_board_shared.sh         App 请求合并/缓存/页签"
echo "  tests/adb_only/test_hid_driver_device.sh  HidDriver 经板子注入"
echo "  tests/server/*.sh                      在服务器上跑(临时 MySQL,不碰线上库)"
echo
if [ $FAILS -eq 0 ]; then echo "全部通过"; else echo "失败 $FAILS 项"; exit 1; fi
