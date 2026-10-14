#!/usr/bin/env bash
# 编译并运行流程解析器单元测试(电脑上跑,不需要板子)
# 用法: bash tests/native/run.sh
set -eu
cd "$(dirname "$0")/../.."
CXX=${CXX:-$(command -v clang++ || command -v g++)}   # 有哪个用哪个
OUT=$(mktemp -t script_parser_test.XXXXXX)
trap 'rm -f "$OUT"' EXIT
"$CXX" -std=c++17 -Wall -Wextra -Werror -O1 -fsanitize=address,undefined \
  -I src/common src/common/script_parser.cpp tests/native/test_script_parser.cpp -o "$OUT"
"$OUT" tests/native/fixtures/全语法.txt
