#!/usr/bin/env bash
# ============================================================================
#  板子名字 + 备注 验证(在服务器上跑)
#
#  起临时 MySQL(端口 3411)+ 测试服务(端口 3995),用本分支 server/,不碰正式库。
#  板子 / App 都用 curl 模拟:
#    1. 心跳记下板子名字(ESP32-S3_XXXX);回复里总带服务器那份备注
#    2. 后台改备注:采用 → 列表 / 详情 / 心跳回复都带上,记审计
#    3. 不能重名(忽略大小写)、前后空格去掉、清空;不合法的(太长 / 引号 / 尖括号 / 表情太多)拒绝
#    4. 板子网页改(板子转发):采用 / 重名 409 / 没见过的板子 404 / token 不对 401
#    5. 板子离线时改的(心跳带 remarkPending):不重名就采用;重名就不采用、记冲突、回复给回服务器那份;
#       后台再改一次冲突就清掉;没标待同步的旧值不会覆盖服务器
#    6. App 改(查重)+ App 代报(带待同步备注时同样查重,回复里带 remark)
#    7. 两个请求同时抢同一个备注:只有一个成功
#    8. 统计 / 事件里带备注;删掉板子后备注可以给别的板子用
#
#  用法(仓库根目录): bash tests/server/test_board_remark.sh
#  ⚠️ bash 变量名只能用 ASCII,所以变量是英文 + 中文注释
# ============================================================================
set -u
REPO=$(cd "$(dirname "$0")/../.." && pwd)   # 仓库根目录
PROD_DIR=/root/touchpad-server              # 只借用它的 node_modules(只读)
TMP=/tmp/tp-remark-test                     # 临时目录
MY_PORT=3411                                # 临时 MySQL 端口
HTTP_PORT=3995                              # 测试服务端口
T_USER=tp_test
T_PASS=tp_test_pw_$$
DB=tp_remark                                # 测试库名
FAILS=0
SRV_PID=
SRV="$REPO/server"
URL="http://127.0.0.1:$HTTP_PORT"
# 测试板子 MAC
M_A=aa:bb:cc:00:20:01      # 后台改备注
M_B=aa:bb:cc:00:20:02      # 重名 / 板子网页改
M_C=aa:bb:cc:00:20:03      # 离线改、重名冲突
M_D=aa:bb:cc:00:20:04      # App 改 / 代报
M_E=aa:bb:cc:00:20:05      # 并发抢名字
M_OLD=aa:bb:cc:00:20:06    # 老固件(心跳名 ESP32-Touch)

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "  ❌ $*"; FAILS=$((FAILS + 1)); }
pass() { echo "  ✅ $*"; }
# 判断:$1 实际  $2 期望  $3 说明
eq()   { [ "$1" = "$2" ] && pass "$3" || fail "$3(实际 [$1],期望 [$2])"; }
# 判断:$1 实际  $2 期望包含的文字  $3 说明
has()  { case "$1" in *"$2"*) pass "$3";; *) fail "$3(实际 [$1],应包含 [$2])";; esac; }

# 安全停临时 MySQL 的公共函数
source "$(cd "$(dirname "$0")" && pwd)/lib_tmp_mysql.sh"

cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null
  # 确认临时 MySQL 真的退出了才删目录(见 lib_tmp_mysql.sh)
  stop_tmp_mysql "$TMP/mysqld.pid" "$TMP/data" && rm -rf "$TMP"
  [ -L "$SRV/node_modules" ] && rm -f "$SRV/node_modules"
  [ -f "$SRV/data/.created_by_test" ] && rm -rf "$SRV/data"
}
trap cleanup EXIT

tmysql() { mysql --no-defaults -h127.0.0.1 -P$MY_PORT -uroot -N "$@"; }

# ---------------------------------------------------------------- 0. 准备
log "0. 起临时 MySQL + 测试服务"
if [ -d "$SRV/data" ] && [ ! -f "$SRV/data/.created_by_test" ]; then
  echo "❌ $SRV/data 已存在且不是测试建的,为免误删,中止"; exit 1
fi
rm -rf "$TMP"; mkdir -p "$TMP"
mysqld --no-defaults --initialize-insecure --user=root --datadir="$TMP/data" >"$TMP/init.log" 2>&1 \
  || { echo "初始化失败"; cat "$TMP/init.log"; exit 1; }
mysqld --no-defaults --user=root --datadir="$TMP/data" --port=$MY_PORT --bind-address=127.0.0.1 \
  --socket="$TMP/mysql.sock" --mysqlx=OFF --innodb-buffer-pool-size=32M \
  --performance-schema=OFF --pid-file="$TMP/mysqld.pid" --log-error="$TMP/err.log" &
for i in $(seq 1 30); do tmysql -e "SELECT 1" >/dev/null 2>&1 && break; sleep 1; done
tmysql -e "SELECT 1" >/dev/null 2>&1 || { echo "临时实例没起来"; tail -20 "$TMP/err.log"; exit 1; }
tmysql -e "CREATE USER '$T_USER'@'%' IDENTIFIED BY '$T_PASS'; GRANT ALL ON *.* TO '$T_USER'@'%';"
[ -e "$SRV/node_modules" ] || ln -s "$PROD_DIR/node_modules" "$SRV/node_modules"
if [ ! -d "$SRV/data" ]; then mkdir -p "$SRV/data"; touch "$SRV/data/.created_by_test"; fi

(cd "$SRV" && exec env PORT=$HTTP_PORT DB_HOST=127.0.0.1 DB_PORT=$MY_PORT DB_USER=$T_USER \
  DB_PASS=$T_PASS DB_NAME=$DB node index.js > "$TMP/srv.log" 2>&1) &
SRV_PID=$!
for i in $(seq 1 30); do curl -s "$URL/api/v1/board/health" | grep -q '"ok":true' && break; sleep 0.5; done
curl -s "$URL/api/v1/board/health" | grep -q '"ok":true' || { echo "服务没起来"; tail -20 "$TMP/srv.log"; exit 1; }
ADMIN_PASS=$(sed -n 's/.*密  码: //p' "$TMP/srv.log" | head -1)
curl -s -c "$TMP/ck" -o /dev/null -X POST "$URL/admin/login" -d "username=admin&password=$ADMIN_PASS"
AT=$(tmysql $DB -e "SELECT v FROM settings WHERE k='appToken'")     # App token
BT=$(tmysql $DB -e "SELECT v FROM settings WHERE k='boardToken'")   # 板子 token
pass "临时实例和测试服务已启动"

# 从 JSON 文件里取字段:$1 文件  $2 python 表达式(d 是解析结果)
jq_() { python3 -c "import json,sys
d=json.load(open(sys.argv[1]))
try: v=eval(sys.argv[2])
except Exception as e: v='<无>'
print('' if v is None else ('True' if v is True else ('False' if v is False else v)))" "$1" "$2"; }

# 板子心跳:$1 MAC  $2 名字  $3 额外 JSON 字段(可省)⇒ 回复存到 $TMP/hb.json
hb() {
  local extra=${3:-}
  curl -s -o "$TMP/hb.json" -X POST "$URL/api/v1/board/heartbeat" -H "X-Token: $BT" \
    -H 'Content-Type: application/json' \
    -d "{\"mac\":\"$1\",\"name\":\"$2\",\"fw\":\"1.5.0\",\"caps\":\"ota-remote\",\"heap\":100000,\"uptime\":10,\"flows\":0${extra:+,$extra}}"
}
# 后台改备注:$1 MAC  $2 备注(JSON 字符串字面量,含引号)⇒ 输出 HTTP 码,回复在 $TMP/adm.json
adm_remark() {
  curl -s -o "$TMP/adm.json" -w '%{http_code}' -b "$TMP/ck" -X POST "$URL/api/admin/boards/$1/remark" \
    -H 'Content-Type: application/json' -d "{\"remark\":$2}"
}
# 板子网页改(板子转发):$1 MAC  $2 备注(JSON 字面量) $3 token(可省)⇒ HTTP 码,回复 $TMP/brd.json
brd_remark() {
  curl -s -o "$TMP/brd.json" -w '%{http_code}' -X POST "$URL/api/v1/board/remark" -H "X-Token: ${3:-$BT}" \
    -H 'Content-Type: application/json' -d "{\"mac\":\"$1\",\"remark\":$2}"
}
# App 改:$1 MAC  $2 备注(JSON 字面量)⇒ HTTP 码,回复 $TMP/app.json
app_remark() {
  curl -s -o "$TMP/app.json" -w '%{http_code}' -X POST "$URL/api/v1/app/board-remark" -H "X-Token: $AT" \
    -H 'Content-Type: application/json' -d "{\"boardMac\":\"$1\",\"deviceId\":\"test-phone\",\"remark\":$2}"
}
# 库里的备注 / 冲突:$1 MAC  $2 列名
col() { tmysql $DB -e "SELECT $2 FROM boards WHERE mac='$1'"; }

# ---------------------------------------------------------------- 1. 心跳
log "1. 心跳记下名字,回复里带服务器那份备注"
for m in $M_A $M_B $M_C $M_D $M_E; do hb $m "ESP32-S3_${m: -5:2}${m: -2}"; done
eq "$(col $M_A name)" "ESP32-S3_2001" "心跳名字入库(ESP32-S3_XXXX)"
eq "$(jq_ "$TMP/hb.json" "'remark' in d")" "True" "心跳回复里有 remark 字段"
eq "$(jq_ "$TMP/hb.json" "d['remark']")" "" "没起过备注 ⇒ 空串(不是 null,板子按字符串解析)"
curl -s -o "$TMP/hb.json" -X POST "$URL/api/v1/board/heartbeat" -H "X-Token: $BT" -H 'Content-Type: application/json' \
  -d "{\"mac\":\"$M_OLD\",\"name\":\"ESP32-Touch\",\"fw\":\"1.3.0\",\"heap\":1,\"uptime\":1,\"flows\":0}"
eq "$(col $M_OLD name)" "ESP32-Touch" "老固件照常记名字"

# ---------------------------------------------------------------- 2. 后台改
log "2. 后台改备注"
eq "$(adm_remark $M_A '"大麦1号"')" 200 "后台改备注 → 200"
has "$(jq_ "$TMP/adm.json" "d['message']")" "下次心跳" "提示板子下次心跳同步"
eq "$(col $M_A remark)" "大麦1号" "库里写上了"
hb $M_A ESP32-S3_2001
eq "$(jq_ "$TMP/hb.json" "d['remark']")" "大麦1号" "心跳回复把备注带给板子"
curl -s -b "$TMP/ck" -o "$TMP/list.json" "$URL/api/admin/boards"
eq "$(jq_ "$TMP/list.json" "[b['remark'] for b in d['boards'] if b['mac']=='$M_A'][0]")" "大麦1号" "后台列表带备注"
eq "$(jq_ "$TMP/list.json" "[b['remarkConflict'] for b in d['boards'] if b['mac']=='$M_A'][0]")" "" "没有冲突 ⇒ remarkConflict 为空"
curl -s -b "$TMP/ck" -o "$TMP/det.json" "$URL/api/admin/board/$M_A"
eq "$(jq_ "$TMP/det.json" "d['board']['remark']")" "大麦1号" "板子详情带备注"
eq "$(tmysql $DB -e "SELECT COUNT(*) FROM audit_log WHERE action='board_remark' AND target='$M_A'")" 1 "记了审计"
adm_remark $M_A '"大麦1号"' >/dev/null
eq "$(tmysql $DB -e "SELECT COUNT(*) FROM audit_log WHERE action='board_remark' AND target='$M_A'")" 1 "没变化不重复记审计"
eq "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/api/admin/boards/$M_A/remark" -H 'Content-Type: application/json' -d '{"remark":"x"}')" 401 "没登录 ⇒ 401"

# ---------------------------------------------------------------- 3. 查重 / 校验
log "3. 不能重名、去空格、清空、拒绝不合法的"
eq "$(adm_remark $M_B '"大麦1号"')" 409 "和 A 重名 ⇒ 409"
has "$(jq_ "$TMP/adm.json" "d['message']")" "已经被" "提示被谁用了"
eq "$(col $M_B remark)" "" "重名时 B 没被改"
eq "$(adm_remark $M_B '"DM01"')" 200 "B 改成 DM01"
eq "$(adm_remark $M_C '"dm01"')" 409 "dm01 和 DM01 只差大小写 ⇒ 也算重名"
eq "$(adm_remark $M_C '"  大麦3号  "')" 200 "前后带空格"
eq "$(col $M_C remark)" "大麦3号" "空格去掉了"
eq "$(adm_remark $M_C '""')" 200 "留空 ⇒ 清掉"
eq "$(col $M_C remark)" "" "清掉了"
eq "$(adm_remark $M_C '""')" 200 "两块板子都没备注不算重名"
eq "$(adm_remark $M_C '"一二三四五六七八九十一二三四五六七八九十"')" 200 "正好 20 个字可以"
eq "$(adm_remark $M_C '"一二三四五六七八九十一二三四五六七八九十一"')" 400 "21 个字 ⇒ 400"
eq "$(adm_remark $M_C '"a\"b"')" 400 "带引号 ⇒ 400(板子不解析转义)"
eq "$(adm_remark $M_C '"a\\b"')" 400 "带反斜杠 ⇒ 400"
eq "$(adm_remark $M_C '"<b>x"')" 400 "带尖括号 ⇒ 400"
eq "$(adm_remark $M_C '"a\nb"')" 400 "带换行 ⇒ 400"
eq "$(adm_remark $M_C '"😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀"')" 400 "16 个表情(64 字节,超过板子 60 字节)⇒ 400"
eq "$(col $M_C remark)" "一二三四五六七八九十一二三四五六七八九十" "拒绝的都没写进库"
eq "$(adm_remark aa:bb:cc:99:99:99 '"x"')" 404 "没见过的板子 ⇒ 404"

# ---------------------------------------------------------------- 4. 板子网页改(板子转发)
log "4. 板子网页改备注(板子转发给服务器查重)"
eq "$(brd_remark $M_B '"大麦2号"')" 200 "不重名 ⇒ 200"
eq "$(jq_ "$TMP/brd.json" "d['remark']")" "大麦2号" "回复里给出采用的备注"
eq "$(col $M_B remark)" "大麦2号" "库里写上了"
eq "$(brd_remark $M_B '"大麦1号"')" 409 "和 A 重名 ⇒ 409"
has "$(jq_ "$TMP/brd.json" "d['message']")" "已经被" "409 带原因(板子网页显示)"
case "$(jq_ "$TMP/brd.json" "d['message']")" in *'"'*|*'\'*) fail "message 里有引号/反斜杠(板子解析不了)";; *) pass "message 里没有引号/反斜杠";; esac
eq "$(brd_remark aa:bb:cc:99:99:98 '"x"')" 404 "服务器没见过的板子 ⇒ 404(板子会先存着、心跳再同步)"
eq "$(brd_remark $M_B '"x"' wrong-token)" 401 "板子 token 不对 ⇒ 401"
eq "$(tmysql $DB -e "SELECT COUNT(*) FROM audit_log WHERE action='board_remark' AND target='$M_B' AND detail LIKE '板子网页%'")" 1 "板子网页改的也记审计"

# ---------------------------------------------------------------- 5. 板子离线时改的(心跳带待同步)
log "5. 心跳带待同步备注"
adm_remark $M_C '"大麦3号"' >/dev/null
hb $M_D ESP32-S3_2004 '"remark":"大麦4号","remarkPending":true'
eq "$(col $M_D remark)" "大麦4号" "不重名 ⇒ 采用"
eq "$(jq_ "$TMP/hb.json" "d['remark']")" "大麦4号" "回复给回采用的值(板子清掉待同步)"
hb $M_C ESP32-S3_2003 '"remark":"大麦1号","remarkPending":true'
eq "$(col $M_C remark)" "大麦3号" "和 A 重名 ⇒ 不采用,服务器还是原来的"
eq "$(jq_ "$TMP/hb.json" "d['remark']")" "大麦3号" "回复给回服务器那份(板子改回去)"
eq "$(col $M_C remark_conflict)" "大麦1号" "记下没采用的备注"
curl -s -b "$TMP/ck" -o "$TMP/list.json" "$URL/api/admin/boards"
eq "$(jq_ "$TMP/list.json" "[b['remarkConflict']['remark'] for b in d['boards'] if b['mac']=='$M_C'][0]")" "大麦1号" "后台列表标出冲突"
eq "$(jq_ "$TMP/list.json" "[b['remarkConflict']['at'] > 0 for b in d['boards'] if b['mac']=='$M_C'][0]")" "True" "冲突带时间"
hb $M_C ESP32-S3_2003 '"remark":"大麦3号"'
eq "$(col $M_C remark_conflict)" "大麦1号" "后续正常心跳不会清掉冲突记录"
hb $M_C ESP32-S3_2003 '"remark":"旧的值"'
eq "$(col $M_C remark)" "大麦3号" "没标待同步的值不会覆盖服务器(以服务器为准)"
eq "$(jq_ "$TMP/hb.json" "d['remark']")" "大麦3号" "回复照样给服务器那份"
hb $M_C ESP32-S3_2003 '"remark":"a\"b","remarkPending":true'
eq "$(col $M_C remark)" "大麦3号" "待同步的不合法 ⇒ 不采用"
eq "$(col $M_C remark_conflict)" 'a"b' "不合法的也记成冲突"
adm_remark $M_C '"大麦5号"' >/dev/null
eq "$(col $M_C remark_conflict)" "" "后台重新改备注 ⇒ 冲突清掉"

# ---------------------------------------------------------------- 6. App
log "6. App 改备注 + App 代报"
eq "$(app_remark $M_D '"大麦1号"')" 409 "App 改成重名的 ⇒ 409"
eq "$(app_remark $M_D '"大麦6号"')" 200 "App 改 ⇒ 200"
eq "$(jq_ "$TMP/app.json" "d['remark']")" "大麦6号" "回复给出采用的值(App 拿它下发给板子)"
eq "$(tmysql $DB -e "SELECT COUNT(*) FROM audit_log WHERE action='board_remark' AND target='$M_D' AND detail LIKE 'App(test-phone)%'")" 1 "App 改的记审计(带手机 id)"
eq "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/api/v1/app/board-remark" -H 'X-Token: bad' -H 'Content-Type: application/json' -d "{\"boardMac\":\"$M_D\",\"remark\":\"x\"}")" 401 "App token 不对 ⇒ 401"
relay() {   # App 代报:$1 MAC  $2 status 里的额外字段 ⇒ 回复 $TMP/relay.json
  curl -s -o "$TMP/relay.json" -X POST "$URL/api/v1/app/board-relay" -H "X-Token: $AT" -H 'Content-Type: application/json' \
    -d "{\"boardMac\":\"$1\",\"deviceId\":\"test-phone\",\"status\":{\"name\":\"ESP32-S3_2004\",\"fw\":\"1.5.0\",\"scriptCount\":0$2}}"
}
relay $M_D ',"remark":"大麦6号","remarkPending":false'
eq "$(jq_ "$TMP/relay.json" "d['remark']")" "大麦6号" "代报回复带服务器那份备注"
eq "$(col $M_D name)" "ESP32-S3_2004" "代报用状态里的名字"
relay $M_D ',"remark":"大麦7号","remarkPending":true'
eq "$(col $M_D remark)" "大麦7号" "代报带待同步的(板子离线时改的)⇒ 查重后采用"
eq "$(jq_ "$TMP/relay.json" "d['remark']")" "大麦7号" "回复给回采用的值"
relay $M_D ',"remark":"大麦1号","remarkPending":true'
eq "$(col $M_D remark)" "大麦7号" "代报带待同步但重名 ⇒ 不采用"
eq "$(col $M_D remark_conflict)" "大麦1号" "记冲突"
relay $M_D ''
eq "$(jq_ "$TMP/relay.json" "d['remark']")" "大麦7号" "老固件代报(没有 remark 字段)⇒ 回复照样带,服务器不变"

# ---------------------------------------------------------------- 7. 并发
log "7. 两块板子同时抢同一个备注"
RACE_BAD=0   # 这一段失败的轮数
for i in 1 2 3 4 5; do
  NEWR="抢$i"
  curl -s -o /dev/null -w '%{http_code}\n' -b "$TMP/ck" -X POST "$URL/api/admin/boards/$M_E/remark" \
    -H 'Content-Type: application/json' -d "{\"remark\":\"$NEWR\"}" > "$TMP/c1" &
  P1=$!
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$URL/api/v1/board/remark" -H "X-Token: $BT" \
    -H 'Content-Type: application/json' -d "{\"mac\":\"$M_B\",\"remark\":\"$NEWR\"}" > "$TMP/c2" &
  P2=$!
  # ⚠️ 只等这两个 curl:不带参数的 wait 会连临时 mysqld / 测试服务一起等,永远等不完
  wait $P1 $P2
  CODES=$(cat "$TMP/c1" "$TMP/c2" | sort | tr '\n' ' ')
  N=$(tmysql $DB -e "SELECT COUNT(*) FROM boards WHERE remark='$NEWR'")
  [ "$CODES" = "200 409 " ] && [ "$N" = 1 ] || { fail "第 $i 轮:返回 [$CODES],库里 $N 块板子叫 $NEWR"; RACE_BAD=$((RACE_BAD + 1)); }
done
[ $RACE_BAD = 0 ] && pass "5 轮同时抢:每轮都是一个 200 一个 409,库里只有一块板子用这个备注"

# ---------------------------------------------------------------- 8. 统计 / 事件 / 删除
log "8. 统计、事件带备注;删掉板子后备注可以复用"
curl -s -o /dev/null -X POST "$URL/api/v1/board/event" -H "X-Token: $BT" -H 'Content-Type: application/json' \
  -d "{\"mac\":\"$M_A\",\"type\":\"flow_event\",\"flow\":\"抢票\",\"message\":\"抢到了\"}"
curl -s -b "$TMP/ck" -o "$TMP/st.json" "$URL/api/admin/event-stats?days=30"
eq "$(jq_ "$TMP/st.json" "d['stats'][0]['board_remark']")" "大麦1号" "成功统计带备注"
curl -s -b "$TMP/ck" -o "$TMP/ev.json" "$URL/api/admin/events"
eq "$(jq_ "$TMP/ev.json" "d['events'][0]['board_remark']")" "大麦1号" "事件明细带备注"
curl -s -b "$TMP/ck" -o /dev/null -X DELETE "$URL/api/admin/board/$M_A"
eq "$(adm_remark $M_B '"大麦1号"')" 200 "A 删掉后「大麦1号」可以给 B 用"

grep -E "Error|错误|出错" "$TMP/srv.log" | head -5

echo
[ $FAILS = 0 ] && echo "全部通过 ✅" || echo "失败 $FAILS 项 ❌"
exit $FAILS
