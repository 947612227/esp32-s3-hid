#!/usr/bin/env bash
# ============================================================================
#  优化11b 验证:流程版本号 flows.ver 改为全局计数器原子取号(在服务器上跑)
#
#  起临时 MySQL(端口 3407,独立数据目录,不碰正式库):
#    1. 旧服务端(取号 = SELECT MAX(ver)+1)写一批数据,并发保存 ⇒ 反向对照:会撞号
#    2. 同一个库换新服务端启动 ⇒ 迁移 002 建 counters,种子 = 现有最大 ver
#       - 新号一定比所有旧号大(跨 owner)
#       - 并发保存 / 删除 / 后台改状态:号全部不同、连续
#       - 冲突检测(baseVer)照常:旧 baseVer → 409,当前 baseVer → 200
#       - 拉取接口的 ver = 启用流程的最大 ver
#       - 重启:迁移不重复执行,计数器不回退
#    3. 全新库:计数器从 0 开始,第一条 ver = 1
#
#  用法(仓库根目录): bash tests/server/test_flow_ver.sh [旧版本 git 引用,默认 aa83aa1]
#  ⚠️ bash 变量名只能用 ASCII,所以变量是英文 + 中文注释
# ============================================================================
set -u
REPO=$(cd "$(dirname "$0")/../.." && pwd)   # 仓库根目录
OLD_REF=${1:-aa83aa1}                       # 旧服务端(还没有计数器)
PROD_DIR=/root/touchpad-server              # 只借用它的 node_modules(只读)
TMP=/tmp/tp-flowver-test                    # 临时目录
MY_PORT=3407                                # 临时 MySQL 端口
HTTP_PORT=3996                              # 测试服务端口
T_USER=tp_test
T_PASS=tp_test_pw_$$
FAILS=0
SRV_PID=
N_PAR=40                                    # 并发请求数

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

tmysql() { mysql --no-defaults -h127.0.0.1 -P$MY_PORT -uroot -N "$@"; }

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
pass "临时实例已启动"

NEW_SRV="$REPO/server"
OLD_SRV="$TMP/old/server"
mkdir -p "$TMP/old"
(cd "$REPO" && git archive "$OLD_REF" server) | tar -x -C "$TMP/old" || { echo "取旧服务端失败"; exit 1; }
[ -e "$NEW_SRV/node_modules" ] || ln -s "$PROD_DIR/node_modules" "$NEW_SRV/node_modules"
ln -s "$PROD_DIR/node_modules" "$OLD_SRV/node_modules"
if [ ! -d "$NEW_SRV/data" ]; then mkdir -p "$NEW_SRV/data"; touch "$NEW_SRV/data/.created_by_test"; fi
mkdir -p "$OLD_SRV/data"

start_server() {   # $1 = 服务端目录  $2 = 库名  $3 = 日志名
  (cd "$1" && PORT=$HTTP_PORT DB_HOST=127.0.0.1 DB_PORT=$MY_PORT DB_USER=$T_USER \
    DB_PASS=$T_PASS DB_NAME=$2 exec node index.js > "$TMP/$3.log" 2>&1) &
  SRV_PID=$!
  for i in $(seq 1 30); do
    curl -s "http://127.0.0.1:$HTTP_PORT/api/v1/board/health" | grep -q '"ok":true' && return 0
    sleep 0.5
  done
  echo "  服务没起来:"; tail -20 "$TMP/$3.log"; return 1
}
stop_server() { kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=; }

AT=   # App token(每个库各自的)
save() {   # $1 = owner  $2 = 流程名  [$3 = baseVer] → 打印 "HTTP码 响应"
  # ⚠️ 文件名要在 $(...) 外面定:里面是子 shell,$BASHPID 不一样
  local code f="$TMP/r.$BASHPID.$RANDOM"
  code=$(curl -s -o "$f" -w '%{http_code}' -X POST "http://127.0.0.1:$HTTP_PORT/api/v1/app/flows" \
    -H "X-Token: $AT" -H 'Content-Type: application/json' \
    -d "{\"deviceId\":\"$1\",\"name\":\"$2\",\"text\":\"wait 1\",\"baseVer\":${3:-0}}")
  echo "$code $(cat "$f")"; rm -f "$f"
}
del() {    # $1 = owner  $2 = 流程名(ASCII)
  curl -s -X DELETE "http://127.0.0.1:$HTTP_PORT/api/v1/app/flows/$2?deviceId=$1" -H "X-Token: $AT"
  echo   # ⚠️ 响应没有换行:不补的话 sed 输出也没换行,并发追加时几个号会粘成一行
}
ver_of() { tmysql fv -e "SELECT ver FROM flows WHERE owner='$1' AND name='$2'"; }
# 并发跑 N 个保存,每个的返回 ver 写进 $1 文件
par_save() {   # $1 = 输出文件  $2 = 名字前缀  $3 = 个数  $4.. = owner 轮流用
  local out=$1 pre=$2 n=$3; shift 3; local owners=("$@") i pids=()
  : > "$out"
  for i in $(seq 1 "$n"); do
    ( save "${owners[$((i % ${#owners[@]}))]}" "${pre}$i" | sed -n 's/.*"ver":\([0-9]*\).*/\1/p' >> "$out" ) &
    pids+=($!)
  done
  wait "${pids[@]}"   # ⚠️ 不能裸 wait:会连后台的测试服务一起等,永远不返回
}
dup_count() { sort -n "$1" | uniq -d | wc -l; }

# ---------------------------------------------------------------- 1. 旧服务端
log "1. 旧服务端($OLD_REF:取号 = MAX(ver)+1)"
start_server "$OLD_SRV" fv old || exit 1
AT=$(tmysql fv -e "SELECT v FROM settings WHERE k='appToken'")
ADMIN_PASS=$(sed -n 's/.*密  码: //p' "$TMP/old.log" | head -1)
for n in a b c; do save d1 "old_$n" > /dev/null; done
save d2 old_x > /dev/null
info "d1 的 ver:$(tmysql fv -e "SELECT GROUP_CONCAT(ver ORDER BY ver) FROM flows WHERE owner='d1'"),d2 的 ver:$(ver_of d2 old_x)"
[ "$(ver_of d2 old_x)" = 1 ] && pass "反向对照:旧版号只在 owner 内递增(d2 又从 1 开始)" \
  || fail "旧版 d2 的 ver 应为 1,实际 $(ver_of d2 old_x)"
par_save "$TMP/old_par.txt" oldpar_ $N_PAR d1
OLD_DUP=$(dup_count "$TMP/old_par.txt")
info "旧版 $N_PAR 个并发保存:拿到 $(sort -un "$TMP/old_par.txt" | wc -l) 个不同的号,重复的号 $OLD_DUP 个"
[ "$OLD_DUP" -gt 0 ] && pass "反向对照:旧版并发保存会撞号" \
  || info "(这次旧版碰巧没撞号 —— 不影响新版判定)"
stop_server
OLD_MAX=$(tmysql fv -e "SELECT MAX(ver) FROM flows")
info "升级前 flows 最大 ver = $OLD_MAX"

# ---------------------------------------------------------------- 2. 新服务端(同一个库)
log "2. 新服务端(同一个库升级)"
start_server "$NEW_SRV" fv new1 || exit 1
grep -E "\[迁移\]|计数器|counters" "$TMP/new1.log" | sed 's/^/  /'
C0=$(tmysql fv -e "SELECT n FROM counters WHERE name='flow_ver'")
[ "$C0" = "$OLD_MAX" ] && pass "迁移 002:计数器种子 = 现有最大 ver($C0)" \
  || fail "计数器种子 $C0,应为 $OLD_MAX"

r=$(save d2 new_y)
v=$(echo "$r" | sed -n 's/.*"ver":\([0-9]*\).*/\1/p')
[ "$v" = $((OLD_MAX + 1)) ] && pass "新号比所有旧号大(d2 新流程 ver=$v,跨 owner 也递增)" \
  || fail "d2 新流程 ver=$v,应为 $((OLD_MAX + 1)):$r"

par_save "$TMP/new_par.txt" newpar_ $N_PAR d1 d2 d3
NEW_N=$(grep -c . "$TMP/new_par.txt"); NEW_DUP=$(dup_count "$TMP/new_par.txt")
LO=$(sort -n "$TMP/new_par.txt" | head -1); HI=$(sort -n "$TMP/new_par.txt" | tail -1)
[ "$NEW_N" = $N_PAR ] && [ "$NEW_DUP" = 0 ] && [ $((HI - LO + 1)) = $N_PAR ] \
  && pass "$N_PAR 个并发保存(3 个 owner 混合):号全部不同且连续($LO~$HI)" \
  || fail "并发保存:返回 $NEW_N 个,重复 $NEW_DUP 个,范围 $LO~$HI"

# 并发混合:删除 + 保存
: > "$TMP/mix.txt"; MIX_PIDS=()
for i in $(seq 1 10); do
  ( del d1 "newpar_$((i * 3))" | sed -n 's/.*"ver":\([0-9]*\).*/\1/p' >> "$TMP/mix.txt" ) &
  MIX_PIDS+=($!)
  ( save d3 "mix_$i" | sed -n 's/.*"ver":\([0-9]*\).*/\1/p' >> "$TMP/mix.txt" ) &
  MIX_PIDS+=($!)
done
wait "${MIX_PIDS[@]}"
MIX_N=$(grep -c . "$TMP/mix.txt"); MIX_DUP=$(dup_count "$TMP/mix.txt")
[ "$MIX_N" = 20 ] && [ "$MIX_DUP" = 0 ] && pass "并发 10 删除 + 10 保存:20 个号全部不同" \
  || fail "并发删除+保存:返回 $MIX_N 个,重复 $MIX_DUP 个"
DBDUP=$(tmysql fv -e "SELECT COUNT(*) FROM (SELECT ver FROM flows WHERE ver > $OLD_MAX GROUP BY ver HAVING COUNT(*) > 1) t")
[ "$DBDUP" = 0 ] && pass "库里升级后写入的行没有重复 ver" || fail "库里有 $DBDUP 个重复 ver"

# 后台改状态也走计数器
curl -s -c "$TMP/ck" -o /dev/null -X POST "http://127.0.0.1:$HTTP_PORT/admin/login" \
  -d "username=admin&password=$ADMIN_PASS"
before=$(tmysql fv -e "SELECT n FROM counters WHERE name='flow_ver'")
r=$(curl -s -b "$TMP/ck" -X POST "http://127.0.0.1:$HTTP_PORT/api/admin/flows/status" \
  -H 'Content-Type: application/json' -d '{"owner":"d1","name":"old_a","status":"disabled"}')
v=$(echo "$r" | sed -n 's/.*"ver":\([0-9]*\).*/\1/p')
[ "$v" = $((before + 1)) ] && [ "$(ver_of d1 old_a)" = "$v" ] && pass "后台改状态:取号 $v 并写进流程" \
  || fail "后台改状态:返回 $r,计数器原为 $before,库里 ver=$(ver_of d1 old_a)"

# 冲突检测
cur=$(ver_of d2 new_y)
r=$(save d2 new_y $((cur - 1)))
[[ "$r" == 409* ]] && pass "冲突检测:基于旧版本(baseVer=$((cur - 1)))保存 → 409" || fail "旧 baseVer 应 409:$r"
r=$(save d2 new_y "$cur")
[[ "$r" == 200* ]] && pass "冲突检测:基于当前版本(baseVer=$cur)保存 → 200" || fail "当前 baseVer 应 200:$r"

# 拉取接口的 ver
pv=$(curl -s "http://127.0.0.1:$HTTP_PORT/api/v1/app/flows?deviceId=d9&since=0" -H "X-Token: $AT" \
  | sed -n 's/^{"ok":true,"ver":\([0-9]*\).*/\1/p')
ev=$(tmysql fv -e "SELECT MAX(ver) FROM flows WHERE deleted=0 AND status='enabled'")
[ "$pv" = "$ev" ] && pass "拉取接口 ver = 启用流程最大 ver($pv)" || fail "拉取 ver=$pv,库里 $ev"
if grep -qE "TypeError|ReferenceError|计数器 .* 不存在|ER_" "$TMP/new1.log"; then
  fail "新服务端日志有异常:"; grep -nE "TypeError|ReferenceError|计数器 .* 不存在|ER_" "$TMP/new1.log" | head -3
else pass "新服务端日志无异常"; fi
stop_server

C1=$(tmysql fv -e "SELECT n FROM counters WHERE name='flow_ver'")
start_server "$NEW_SRV" fv new2 || exit 1
C2=$(tmysql fv -e "SELECT n FROM counters WHERE name='flow_ver'")
grep -q "已是最新" "$TMP/new2.log" && [ "$C1" = "$C2" ] \
  && pass "重启:迁移不重复执行,计数器不回退($C2)" || fail "重启后:$(grep '\[迁移\]' "$TMP/new2.log") 计数器 $C1 → $C2"
stop_server

# ---------------------------------------------------------------- 3. 全新库
log "3. 全新库"
start_server "$NEW_SRV" fv_fresh fresh || exit 1
AT=$(tmysql fv_fresh -e "SELECT v FROM settings WHERE k='appToken'")
r=$(save d1 first)
[[ "$r" == *'"ver":1}'* ]] && pass "全新库第一条流程 ver=1" || fail "全新库第一条:$r"
stop_server

echo
[ $FAILS -eq 0 ] && echo "全部通过" || echo "失败 $FAILS 项"
exit $FAILS
