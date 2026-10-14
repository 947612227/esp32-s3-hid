#!/usr/bin/env bash
# ============================================================================
#  数据库结构 / 迁移 测试(在服务器上跑)
#
#  起一个**临时的独立 MySQL 实例**(独立数据目录 + 端口 3407 + 不读 /etc/my.cnf),
#  完全不碰正式库,验证三种场景:
#    A. 全新部署:空实例 → 启动服务(schema.sql + 迁移)
#    B. 老库升级:先按旧版 schema.sql 建库 → 启动新服务 → 迁移补列
#    C. 正式库结构:把正式库的表结构(只读导出)灌进来 → 启动新服务 → 迁移应全部跳过
#  每种场景都:① 和正式库的结构逐列对比 ② 调一遍真实接口(心跳/事件/连接方式/流程同步)
#
#  用法(仓库根目录):
#    bash tests/server/test_schema_deploy.sh [旧版本 git 引用,默认 d4b105c]
#
#  ⚠️ bash 变量名只能用 ASCII,所以变量是英文 + 中文注释
# ============================================================================
set -u
REPO=$(cd "$(dirname "$0")/../.." && pwd)   # 仓库根目录
SRV="$REPO/server"                          # 待测的服务端代码
OLD_REF=${1:-d4b105c}                       # 旧 schema.sql 取自这个提交
PROD_DIR=/root/touchpad-server              # 正式服务(只读:导出表结构 + 借用 node_modules)
TMP=/tmp/tp-schema-test                     # 临时实例的数据目录
MY_PORT=3407                                # 临时 MySQL 端口
HTTP_PORT=3999                              # 测试服务端口
T_USER=tp_test                              # 临时实例里的测试账号
T_PASS=tp_test_pw_$$
FAILS=0                                     # 失败项计数
SRV_PID=

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "  ❌ $*"; FAILS=$((FAILS + 1)); }
pass() { echo "  ✅ $*"; }

# 退出时清理:停服务、停临时实例、删临时目录和本测试建的软链接/数据目录
cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null
  if [ -f "$TMP/mysqld.pid" ]; then kill "$(cat "$TMP/mysqld.pid")" 2>/dev/null; sleep 2; fi
  rm -rf "$TMP"
  [ -L "$SRV/node_modules" ] && rm -f "$SRV/node_modules"
  [ -f "$SRV/data/.created_by_test" ] && rm -rf "$SRV/data"
}
trap cleanup EXIT

# 连临时实例(root 无密码,只在临时实例里)
tmysql() { mysql --no-defaults -h127.0.0.1 -P$MY_PORT -uroot "$@"; }

# ---------------------------------------------------------------- 0. 准备
log "0. 起临时 MySQL 实例(端口 $MY_PORT,数据目录 $TMP)"
rm -rf "$TMP"; mkdir -p "$TMP"
mysqld --no-defaults --initialize-insecure --user=root --datadir="$TMP/data" >"$TMP/init.log" 2>&1 \
  || { echo "初始化失败"; cat "$TMP/init.log"; exit 1; }
mysqld --no-defaults --user=root --datadir="$TMP/data" --port=$MY_PORT --bind-address=127.0.0.1 \
  --socket="$TMP/mysql.sock" --mysqlx=OFF --innodb-buffer-pool-size=32M \
  --performance-schema=OFF --pid-file="$TMP/mysqld.pid" --log-error="$TMP/err.log" &
for i in $(seq 1 30); do tmysql -e "SELECT 1" >/dev/null 2>&1 && break; sleep 1; done
tmysql -e "SELECT 1" >/dev/null 2>&1 || { echo "临时实例没起来"; tail -20 "$TMP/err.log"; exit 1; }
tmysql -e "CREATE USER '$T_USER'@'%' IDENTIFIED BY '$T_PASS'; GRANT ALL ON *.* TO '$T_USER'@'%';"
pass "临时实例已启动:MySQL $(tmysql -N -e 'SELECT VERSION()')"

# 服务依赖:借正式服务的 node_modules(软链接,测完删)
[ -e "$SRV/node_modules" ] || ln -s "$PROD_DIR/node_modules" "$SRV/node_modules"
if [ ! -d "$SRV/data" ]; then mkdir -p "$SRV/data"; touch "$SRV/data/.created_by_test"; fi

# 正式库结构(只读导出,用正式服务自己的连接配置)
log "导出正式库表结构(只读)"
(cd "$PROD_DIR" && node -e '
const db=require("./src/db");
(async()=>{const ts=await db.q("SHOW TABLES");
 for(const t of ts){const n=Object.values(t)[0];const r=await db.one("SHOW CREATE TABLE `"+n+"`");
  console.log(r["Create Table"].replace(/ AUTO_INCREMENT=\d+/,"")+";");}
 await db.close();})().catch(e=>{console.error(e.message);process.exit(1)});') > "$TMP/prod_tables.sql" \
  || { echo "导出正式库结构失败"; exit 1; }
# SHOW TABLES 是按字母序的,sessions 的外键指向后面才建的 users ⇒ 导入时先关外键检查
{ echo "SET FOREIGN_KEY_CHECKS=0;"; cat "$TMP/prod_tables.sql"; echo "SET FOREIGN_KEY_CHECKS=1;"; } > "$TMP/prod.sql"
tmysql -e "CREATE DATABASE tp_ref CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
tmysql tp_ref < "$TMP/prod.sql" || { echo "正式库结构导入临时实例失败"; exit 1; }

# 导出某个库的规范化结构(mysql2 从正式服务的 node_modules 里找);失败直接算测试失败
dump_schema() {
  NODE_PATH="$PROD_DIR/node_modules" DB_HOST=127.0.0.1 DB_PORT=$MY_PORT DB_USER=$T_USER \
    DB_PASS=$T_PASS DB_NAME=$1 node "$REPO/tests/server/schema_dump.js" > "$2" \
    || { fail "导出 $1 的结构失败"; return 1; }
  [ -s "$2" ] || { fail "导出 $1 的结构为空"; return 1; }
}
dump_schema tp_ref "$TMP/ref.txt" || exit 1
pass "正式库结构:$(grep -c '^列' "$TMP/ref.txt") 列,$(grep -c '^索引' "$TMP/ref.txt") 个索引,$(grep '^列' "$TMP/ref.txt" | awk '{print $2}' | cut -d. -f1 | sort -u | wc -l) 张表"

# ---------------------------------------------------------------- 公共:启动服务 / 调接口 / 对比结构
start_server() {   # $1 = 库名
  (cd "$SRV" && PORT=$HTTP_PORT DB_HOST=127.0.0.1 DB_PORT=$MY_PORT DB_USER=$T_USER \
    DB_PASS=$T_PASS DB_NAME=$1 exec node index.js > "$TMP/server_$1.log" 2>&1) &
  SRV_PID=$!
  for i in $(seq 1 30); do
    curl -s "http://127.0.0.1:$HTTP_PORT/api/v1/board/health" | grep -q '"ok":true' && return 0
    sleep 0.5
  done
  echo "  服务没起来,日志:"; tail -20 "$TMP/server_$1.log"; return 1
}
stop_server() { kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=; sleep 0.5; }

# 发请求:方法 路径 token [body] → 打印 "HTTP码 响应前 160 字节"
req() {
  local code
  if [ -n "${4:-}" ]; then
    code=$(curl -s -o "$TMP/resp" -w '%{http_code}' -X "$1" "http://127.0.0.1:$HTTP_PORT$2" \
      -H "X-Token: $3" -H 'Content-Type: application/json' -d "$4")
  else
    code=$(curl -s -o "$TMP/resp" -w '%{http_code}' -X "$1" "http://127.0.0.1:$HTTP_PORT$2" -H "X-Token: $3")
  fi
  echo "$code $(head -c 160 "$TMP/resp")"
}
expect() {   # 说明 期望HTTP码 实际结果
  if [[ "$3" == "$2 "* ]]; then pass "$1"; else fail "$1 → $3"; fi
}

call_apis() {   # $1 = 库名
  local db=$1 bt at row
  bt=$(tmysql -N "$db" -e "SELECT v FROM settings WHERE k='boardToken'")   # 板子 token
  at=$(tmysql -N "$db" -e "SELECT v FROM settings WHERE k='appToken'")     # App token
  expect "板子心跳(含流程名/内容)" 200 "$(req POST /api/v1/board/heartbeat "$bt" \
    '{"mac":"AA:BB:CC:DD:EE:01","fw":"1.2.0","flows":1,"flowNames":["测试流程"],"flowTexts":{"测试流程":"wait 100"}}')"
  expect "板子心跳(第二次,内容变化 → 写历史)" 200 "$(req POST /api/v1/board/heartbeat "$bt" \
    '{"mac":"AA:BB:CC:DD:EE:01","fw":"1.2.0","flows":1,"flowNames":["测试流程"],"flowTexts":{"测试流程":"wait 200"}}')"
  expect "板子事件 board_events" 200 "$(req POST /api/v1/board/event "$bt" \
    '{"mac":"AA:BB:CC:DD:EE:01","flow":"测试流程","message":"测试消息"}')"
  expect "App 报连接方式 link_*" 200 "$(req POST /api/v1/app/link "$at" \
    '{"boardMac":"AA:BB:CC:DD:EE:01","mode":"ble","hid":"ESP32S3 Touch HID","deviceId":"测试手机"}')"
  expect "App 上传流程 flows" 200 "$(req POST /api/v1/app/flows "$at" \
    '{"deviceId":"测试手机","name":"测试流程","text":"wait 100","meta":{"enabled":true}}')"
  expect "App 拉取流程" 200 "$(req GET '/api/v1/app/flows?since=0' "$at")"
  expect "App 删除流程" 200 "$(req DELETE '/api/v1/app/flows/%E6%B5%8B%E8%AF%95%E6%B5%81%E7%A8%8B?deviceId=%E6%B5%8B%E8%AF%95%E6%89%8B%E6%9C%BA' "$at")"
  row=$(tmysql -N "$db" -e "SELECT CONCAT(
      (SELECT COUNT(*) FROM boards WHERE link_mode='ble'), '/',
      (SELECT COUNT(*) FROM board_events), '/',
      (SELECT COUNT(*) FROM flow_history), '/',
      (SELECT COUNT(*) FROM flows WHERE deleted=1))")
  if [[ "$row" =~ ^1/1/[1-9][0-9]*/1$ ]]; then pass "落库检查(连接方式/事件/历史/软删)= $row"
  else fail "落库检查(期望 1/1/≥1/1)实际 $row"; fi
  if grep -q -E "Unknown column|doesn't exist|ER_[A-Z_]+" "$TMP/server_$db.log"; then
    fail "服务日志里有 SQL 错误:"; grep -E "Unknown column|doesn't exist|ER_[A-Z_]+" "$TMP/server_$db.log" | head -5
  else
    pass "服务日志无 SQL 错误"
  fi
}

compare_schema() {   # $1 = 库名
  dump_schema "$1" "$TMP/$1.txt" || return
  if diff "$TMP/ref.txt" "$TMP/$1.txt" > "$TMP/$1.diff"; then
    pass "表结构与正式库一致"
  else
    fail "表结构与正式库不一致(< 正式库  > 本库):"; head -30 "$TMP/$1.diff"
  fi
}

# ---------------------------------------------------------------- A. 全新部署
log "A. 全新部署(库还不存在,由服务启动时自己建)"
if start_server tp_fresh; then
  grep "\[迁移\]" "$TMP/server_tp_fresh.log" | sed 's/^/  /'
  compare_schema tp_fresh; call_apis tp_fresh; stop_server
else fail "A 服务启动失败"; fi

# ---------------------------------------------------------------- B. 老库升级
log "B. 老库升级(按 $OLD_REF 的 schema.sql 建库,再启动新服务)"
(cd "$REPO" && git show "$OLD_REF:server/sql/schema.sql") > "$TMP/old_schema.sql" || fail "取旧 schema 失败"
# 旧文件写死了 USE touchpad ⇒ 只会在临时实例里建出 touchpad 库
tmysql < "$TMP/old_schema.sql" || fail "旧 schema 导入失败"
dump_schema touchpad "$TMP/old_only.txt"
# 反向对照:旧 schema 必须能被比出差异,否则说明对比本身是坏的
MISSING=$(diff "$TMP/ref.txt" "$TMP/old_only.txt" | grep -c '^<')
if [ "$MISSING" -gt 0 ]; then pass "对比有效:旧 schema 比正式库缺 $MISSING 项(列/索引)"
else fail "旧 schema 居然和正式库一致 —— 对比逻辑可能有问题"; fi
if start_server touchpad; then
  grep -E "\[迁移\]|加列" "$TMP/server_touchpad.log" | sed 's/^/  /'
  compare_schema touchpad; call_apis touchpad; stop_server
else fail "B 服务启动失败"; fi

# ---------------------------------------------------------------- C. 正式库结构
log "C. 正式库结构(迁移应全部跳过,不改任何列)"
tmysql -e "CREATE DATABASE tp_prodcopy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
tmysql tp_prodcopy < "$TMP/prod.sql" || fail "正式库结构导入失败"
if start_server tp_prodcopy; then
  grep -E "\[迁移\]|加列" "$TMP/server_tp_prodcopy.log" | sed 's/^/  /'
  if grep -q "加列" "$TMP/server_tp_prodcopy.log"; then fail "正式库结构上居然加了列"; else pass "没有加任何列"; fi
  compare_schema tp_prodcopy; call_apis tp_prodcopy; stop_server
  # 再启动一次:迁移已记录,应提示"已是最新"
  if start_server tp_prodcopy; then
    if grep -q "已是最新" "$TMP/server_tp_prodcopy.log"; then pass "重启后迁移不重复执行"; else fail "重启后迁移又执行了"; fi
    stop_server
  fi
else fail "C 服务启动失败"; fi

echo
if [ $FAILS -eq 0 ]; then echo "===== 全部通过 ====="; else echo "===== 失败 $FAILS 项 ====="; fi
exit $FAILS
