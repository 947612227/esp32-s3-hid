#!/usr/bin/env bash
# ============================================================================
#  优化6 验证:心跳里的流程列表按差异同步(在服务器上跑)
#
#  起临时 MySQL(端口 3407,独立数据目录,不碰正式库),分别对
#    新服务端(本分支 server/)和 旧服务端(基线提交的 server/)
#  跑同一组心跳,检查:
#    1. 内容没变的心跳:新服务端 0 条写 board_flows / flow_history 的语句
#    2. 改一条 / 删一条 / 加一条:只动对应行,历史正确
#    3. 心跳不带 flowNames(新固件"流程未变"):两个服务端都不动留底  ← 新固件兼容旧服务端
#    4. App 代报只带名字不带内容:新服务端保留旧内容(旧服务端会被写成 NULL,作反向对照)
#    5. 服务器没有该板子的流程、或有留底内容是 NULL,板子又没带 ⇒ 响应 needFlows:true
#
#  用法(仓库根目录): bash tests/server/test_flow_sync.sh [旧版本 git 引用,默认 d4b105c]
#  ⚠️ bash 变量名只能用 ASCII,所以变量是英文 + 中文注释
# ============================================================================
set -u
REPO=$(cd "$(dirname "$0")/../.." && pwd)   # 仓库根目录
OLD_REF=${1:-d4b105c}                       # 旧服务端取自这个提交
PROD_DIR=/root/touchpad-server              # 只借用它的 node_modules(只读)
TMP=/tmp/tp-flowsync-test                   # 临时目录
MY_PORT=3407                                # 临时 MySQL 端口
HTTP_PORT=3998                              # 测试服务端口
T_USER=tp_test
T_PASS=tp_test_pw_$$
FAILS=0
SRV_PID=
MAC=aa:bb:cc:dd:ee:06                       # 测试用板子 MAC

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "  ❌ $*"; FAILS=$((FAILS + 1)); }
pass() { echo "  ✅ $*"; }
info() { echo "  ·  $*"; }

# 安全停临时 MySQL 的公共函数
source "$(cd "$(dirname "$0")" && pwd)/lib_tmp_mysql.sh"

cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null
  # 确认临时 MySQL 真的退出了才删目录(见 lib_tmp_mysql.sh)
  stop_tmp_mysql "$TMP/mysqld.pid" "$TMP/data" && rm -rf "$TMP"
  [ -L "$REPO/server/node_modules" ] && rm -f "$REPO/server/node_modules"
  [ -f "$REPO/server/data/.created_by_test" ] && rm -rf "$REPO/server/data"
}
trap cleanup EXIT

tmysql() { mysql --no-defaults -h127.0.0.1 -P$MY_PORT -uroot "$@"; }

# ---------------------------------------------------------------- 0. 准备
log "0. 起临时 MySQL 实例(端口 $MY_PORT)"
rm -rf "$TMP"; mkdir -p "$TMP"
mysqld --no-defaults --initialize-insecure --user=root --datadir="$TMP/data" >"$TMP/init.log" 2>&1 \
  || { echo "初始化失败"; cat "$TMP/init.log"; exit 1; }
mysqld --no-defaults --user=root --datadir="$TMP/data" --port=$MY_PORT --bind-address=127.0.0.1 \
  --socket="$TMP/mysql.sock" --mysqlx=OFF --innodb-buffer-pool-size=32M \
  --performance-schema=OFF --pid-file="$TMP/mysqld.pid" --log-error="$TMP/err.log" &
for i in $(seq 1 30); do tmysql -e "SELECT 1" >/dev/null 2>&1 && break; sleep 1; done
tmysql -e "SELECT 1" >/dev/null 2>&1 || { echo "临时实例没起来"; tail -20 "$TMP/err.log"; exit 1; }
tmysql -e "CREATE USER '$T_USER'@'%' IDENTIFIED BY '$T_PASS'; GRANT ALL ON *.* TO '$T_USER'@'%';"
# 通用日志写进表,用来数每次心跳执行了哪些写语句
tmysql -e "SET GLOBAL log_output='TABLE'; SET GLOBAL general_log=1;"
pass "临时实例已启动"

# 新服务端 = 本分支;旧服务端 = 基线提交(git archive 解到临时目录)
NEW_SRV="$REPO/server"
OLD_SRV="$TMP/old/server"
mkdir -p "$TMP/old"
(cd "$REPO" && git archive "$OLD_REF" server) | tar -x -C "$TMP/old" || { echo "取旧服务端失败"; exit 1; }
[ -e "$NEW_SRV/node_modules" ] || ln -s "$PROD_DIR/node_modules" "$NEW_SRV/node_modules"
ln -s "$PROD_DIR/node_modules" "$OLD_SRV/node_modules"
if [ ! -d "$NEW_SRV/data" ]; then mkdir -p "$NEW_SRV/data"; touch "$NEW_SRV/data/.created_by_test"; fi
mkdir -p "$OLD_SRV/data"

start_server() {   # $1 = 服务端目录  $2 = 库名
  (cd "$1" && PORT=$HTTP_PORT DB_HOST=127.0.0.1 DB_PORT=$MY_PORT DB_USER=$T_USER \
    DB_PASS=$T_PASS DB_NAME=$2 exec node index.js > "$TMP/server_$2.log" 2>&1) &
  SRV_PID=$!
  for i in $(seq 1 30); do
    curl -s "http://127.0.0.1:$HTTP_PORT/api/v1/board/health" | grep -q '"ok":true' && return 0
    sleep 0.5
  done
  echo "  服务没起来:"; tail -20 "$TMP/server_$2.log"; return 1
}
stop_server() { kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=; sleep 0.5; }

# 发一次板子心跳,响应存 $TMP/resp;同时统计这次心跳对流程表执行的写语句条数
DB=
hb() {   # hb <json>
  tmysql -e "TRUNCATE mysql.general_log"
  curl -s -o "$TMP/resp" -X POST "http://127.0.0.1:$HTTP_PORT/api/v1/board/heartbeat" \
    -H "X-Token: $BT" -H 'Content-Type: application/json' -d "$1"
  sleep 0.3
  FLOW_WRITES=$(tmysql -N -e "SELECT COUNT(*) FROM mysql.general_log
     WHERE command_type IN ('Query','Execute')
       AND argument REGEXP '^[[:space:]]*(INSERT|UPDATE|DELETE)'
       AND argument REGEXP 'board_flows|flow_history'")
}
relay() {   # relay <json>  App 代报
  curl -s -o "$TMP/resp" -X POST "http://127.0.0.1:$HTTP_PORT/api/v1/app/board-relay" \
    -H "X-Token: $AT" -H 'Content-Type: application/json' -d "$1"
}
flows_dump() {   # 当前留底:名字=内容(按名字排序)
  tmysql -N "$DB" -e "SELECT CONCAT(name,'=',IFNULL(text,'NULL')) FROM board_flows WHERE mac='$MAC' ORDER BY name" | tr '\n' ' '
}
hist_count() { tmysql -N "$DB" -e "SELECT COUNT(*) FROM flow_history WHERE mac='$MAC'"; }

FULL3='{"mac":"'$MAC'","fw":"t","flows":3,"flowNames":["A","B","C"],"flowTexts":{"A":"wait 100","B":"wait 200","C":"wait 300"}}'
CHG_A='{"mac":"'$MAC'","fw":"t","flows":3,"flowNames":["A","B","C"],"flowTexts":{"A":"wait 111","B":"wait 200","C":"wait 300"}}'
DEL_B_ADD_D='{"mac":"'$MAC'","fw":"t","flows":3,"flowNames":["A","C","D"],"flowTexts":{"A":"wait 111","C":"wait 300","D":"wait 400"}}'
NO_FLOWS='{"mac":"'$MAC'","fw":"t","flows":3}'
RELAY_NAMES_ONLY='{"boardMac":"'$MAC'","status":{"scriptCount":3},"flowNames":["A","C","D"]}'

# ---------------------------------------------------------------- 一组用例
run_cases() {   # $1 = 标签(新/旧)
  local label=$1 before after at1 at2
  BT=$(tmysql -N "$DB" -e "SELECT v FROM settings WHERE k='boardToken'")
  AT=$(tmysql -N "$DB" -e "SELECT v FROM settings WHERE k='appToken'")

  hb "$FULL3"
  info "首次全量:写语句 $FLOW_WRITES 条,留底 = $(flows_dump)"

  at1=$(tmysql -N "$DB" -e "SELECT GROUP_CONCAT(at ORDER BY name) FROM board_flows WHERE mac='$MAC'")
  sleep 1.2
  hb "$FULL3"
  at2=$(tmysql -N "$DB" -e "SELECT GROUP_CONCAT(at ORDER BY name) FROM board_flows WHERE mac='$MAC'")
  info "内容没变的心跳:写语句 $FLOW_WRITES 条"
  if [ "$label" = 新 ]; then
    [ "$FLOW_WRITES" = 0 ] && pass "内容没变 ⇒ 0 条写语句" || fail "内容没变却写了 $FLOW_WRITES 条"
    [ "$at1" = "$at2" ] && pass "行没被重写(at 不变)" || fail "at 变了:$at1 → $at2"
  fi

  hb "$CHG_A"
  info "改 A:写语句 $FLOW_WRITES 条,留底 = $(flows_dump),历史 $(hist_count) 条"
  if [ "$label" = 新 ]; then
    [ "$(flows_dump)" = "A=wait 111 B=wait 200 C=wait 300 " ] && pass "A 已更新,B/C 不变" || fail "留底不对:$(flows_dump)"
    [ "$(hist_count)" = 1 ] && pass "旧版 A 进了历史" || fail "历史条数 $(hist_count)"
    [ "$FLOW_WRITES" -le 3 ] && pass "只写了 $FLOW_WRITES 条(历史 + 更新 + 历史清理)" || fail "写了 $FLOW_WRITES 条"
  fi

  hb "$DEL_B_ADD_D"
  info "删 B 加 D:留底 = $(flows_dump)"
  [ "$(flows_dump)" = "A=wait 111 C=wait 300 D=wait 400 " ] && pass "[$label] 删 B 加 D 正确" || fail "[$label] 留底不对:$(flows_dump)"

  before=$(flows_dump)
  hb "$NO_FLOWS"
  after=$(flows_dump)
  [ "$before" = "$after" ] && pass "[$label] 心跳不带 flowNames ⇒ 留底不动" || fail "[$label] 留底被改:$after"
  if [ "$label" = 新 ]; then
    grep -q '"needFlows":false' "$TMP/resp" && pass "服务器有留底 ⇒ needFlows:false" || fail "响应:$(head -c 200 "$TMP/resp")"
  fi

  relay "$RELAY_NAMES_ONLY"
  after=$(flows_dump)
  if [ "$label" = 新 ]; then
    [ "$after" = "$before" ] && pass "App 代报只带名字 ⇒ 旧内容保留" || fail "内容被改:$after"
  else
    if echo "$after" | grep -q "=NULL"; then pass "反向对照:旧服务端确实会把内容写成 NULL($after)"
    else fail "旧服务端居然没写 NULL —— 对照无效:$after"; fi
  fi

  if [ "$label" = 新 ]; then
    # 模拟正式库现状:留底行还在,但内容被旧服务端写成了 NULL
    tmysql "$DB" -e "UPDATE board_flows SET text = NULL WHERE mac='$MAC' AND name='C'"
    hb "$NO_FLOWS"
    grep -q '"needFlows":true' "$TMP/resp" && pass "有留底内容是 NULL ⇒ needFlows:true" \
      || fail "响应:$(head -c 200 "$TMP/resp")"
    # 板子按要求带全量后,NULL 被补回,needFlows 恢复 false
    hb "$DEL_B_ADD_D"
    [ "$(flows_dump)" = "A=wait 111 C=wait 300 D=wait 400 " ] && pass "全量上报后 NULL 内容已补回" \
      || fail "留底不对:$(flows_dump)"
    hb "$NO_FLOWS"
    grep -q '"needFlows":false' "$TMP/resp" && pass "补回后 ⇒ needFlows:false" \
      || fail "响应:$(head -c 200 "$TMP/resp")"

    tmysql "$DB" -e "DELETE FROM board_flows WHERE mac='$MAC'"
    hb "$NO_FLOWS"
    grep -q '"needFlows":true' "$TMP/resp" && pass "服务器没有留底、板子没带 ⇒ needFlows:true" \
      || fail "响应:$(head -c 200 "$TMP/resp")"
  fi
}

log "1. 新服务端(本分支)"
DB=tp_new
if start_server "$NEW_SRV" $DB; then run_cases 新; stop_server; else fail "新服务端启动失败"; fi

log "2. 旧服务端($OLD_REF)"
DB=touchpad   # 旧 schema.sql 写死 USE touchpad
# 旧 db.js 连接时就指定库名 ⇒ 库必须先存在(优化4 修的就是这个)
tmysql -e "CREATE DATABASE touchpad CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
if start_server "$OLD_SRV" $DB; then run_cases 旧; stop_server; else fail "旧服务端启动失败"; fi

for f in "$TMP"/server_*.log; do
  if grep -q -E "Unknown column|doesn't exist|ER_[A-Z_]+|心跳失败" "$f"; then
    fail "$(basename "$f") 里有错误:"; grep -E "Unknown column|doesn't exist|ER_[A-Z_]+|心跳失败" "$f" | head -5
  fi
done
grep "\[flows\]" "$TMP/server_tp_new.log" | sed 's/^/  新服务端日志: /'

echo
if [ $FAILS -eq 0 ]; then echo "===== 全部通过 ====="; else echo "===== 失败 $FAILS 项 ====="; fi
exit $FAILS
