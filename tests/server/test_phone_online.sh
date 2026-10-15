#!/usr/bin/env bash
# ============================================================================
#  设备管理「手机在线」判定验证(在服务器上跑)
#
#  起临时 MySQL(端口 3409)+ 测试服务(端口 3995),用本分支 server/,不碰正式库:
#    1. App 上报手机(/app/phone)→ 设备管理显示在线、版本号对
#    2. last_seen 挪到 10 分钟前 → 显示离线(5 分钟窗口)
#    3. App 的其它请求(代报板子 / 定时 / 连接方式 / 拉流程)带 deviceId → 顺手刷新成在线
#    4. 节流:同一台手机 30 秒内只写一次库
#    5. 没登记过的 deviceId 不会凭空多出一台手机;token 不对不刷新
#
#  用法(仓库根目录): bash tests/server/test_phone_online.sh
#  ⚠️ bash 变量名只能用 ASCII,所以变量是英文 + 中文注释
# ============================================================================
set -u
REPO=$(cd "$(dirname "$0")/../.." && pwd)   # 仓库根目录
PROD_DIR=/root/touchpad-server              # 只借用它的 node_modules(只读)
TMP=/tmp/tp-phone-test                      # 临时目录
MY_PORT=3409                                # 临时 MySQL 端口
HTTP_PORT=3995                              # 测试服务端口
T_USER=tp_test
T_PASS=tp_test_pw_$$
DB=tp_phone                                 # 测试库名
P1=test-phone-0001                          # 测试手机 deviceId
MAC=aa:bb:cc:00:00:01                       # 测试板子 MAC
FAILS=0
SRV_PID=
SRV="$REPO/server"
URL="http://127.0.0.1:$HTTP_PORT"

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "  ❌ $*"; FAILS=$((FAILS + 1)); }
pass() { echo "  ✅ $*"; }

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
AT=$(tmysql $DB -e "SELECT v FROM settings WHERE k='appToken'")
pass "临时实例和测试服务已启动"

# App 请求:$1 方法  $2 路径  $3 JSON(可省)  [$4 token,默认正确的]
app() {
  local tk=${4:-$AT}
  if [ $# -ge 3 ] && [ -n "$3" ]; then
    curl -s -o /dev/null -w '%{http_code}' -X "$1" "$URL$2" -H "X-Token: $tk" \
      -H 'Content-Type: application/json' -d "$3"
  else
    curl -s -o /dev/null -w '%{http_code}' -X "$1" "$URL$2" -H "X-Token: $tk"
  fi
}
# 设备管理里这台手机的 online / appVersion
# ⚠️ 先等 0.5 秒:App 请求里刷新 last_seen 是"发出去不等结果",接口返回时那条 UPDATE 可能还没落库
phone_field() { sleep 0.5; curl -s -b "$TMP/ck" "$URL/api/admin/phones" | python3 -c "import json,sys
d=[p for p in json.load(sys.stdin)['phones'] if p.get('deviceId')==sys.argv[1]]
print(d[0].get(sys.argv[2]) if d else '无此手机')" "$1" "$2"; }
# 把 last_seen 挪到 10 分钟前(模拟很久没消息)
age_phone() { tmysql $DB -e "UPDATE phones SET last_seen = NOW() - INTERVAL 10 MINUTE WHERE device_id='$1'"; }
# 等过节流窗口
wait_throttle() { sleep 31; }

# ---------------------------------------------------------------- 1. 上报手机
log "1. App 上报手机 → 在线 + 版本号"
app POST /api/v1/app/phone "{\"deviceId\":\"$P1\",\"name\":\"测试机\",\"model\":\"T1\",\"android\":\"16\",\"appVersion\":\"3.6\"}" >/dev/null
[ "$(phone_field $P1 online)" = True ] && pass "上报后在线" || fail "上报后不在线"
[ "$(phone_field $P1 appVersion)" = 3.6 ] && pass "版本号 3.6" || fail "版本号=$(phone_field $P1 appVersion)"

# ---------------------------------------------------------------- 2. 过期
log "2. 10 分钟没消息 → 离线"
age_phone $P1
[ "$(phone_field $P1 online)" = False ] && pass "显示离线" || fail "应该离线"

# ---------------------------------------------------------------- 3. 其它请求顺手刷新
log "3. App 的其它请求带 deviceId → 刷新在线(每项之间等过 30 秒节流)"
wait_throttle
age_phone $P1
c=$(app POST /api/v1/app/board-relay "{\"boardMac\":\"$MAC\",\"deviceId\":\"$P1\",\"status\":{\"fw\":\"1.3.0\"}}")
[ "$(phone_field $P1 online)" = True ] && pass "代报板子(HTTP $c)→ 在线" || fail "代报板子后仍离线(HTTP $c)"

wait_throttle; age_phone $P1
c=$(app POST /api/v1/app/schedule "{\"boardMac\":\"$MAC\",\"deviceId\":\"$P1\",\"tasks\":[]}")
[ "$(phone_field $P1 online)" = True ] && pass "上报定时(HTTP $c)→ 在线" || fail "上报定时后仍离线(HTTP $c)"

wait_throttle; age_phone $P1
c=$(app POST /api/v1/app/link "{\"boardMac\":\"$MAC\",\"deviceId\":\"$P1\",\"mode\":\"ble\"}")
[ "$(phone_field $P1 online)" = True ] && pass "上报连接方式(HTTP $c)→ 在线" || fail "上报连接方式后仍离线(HTTP $c)"

wait_throttle; age_phone $P1
c=$(app GET "/api/v1/app/flows?deviceId=$P1&since=0")
[ "$(phone_field $P1 online)" = True ] && pass "拉流程(deviceId 在查询参数里,HTTP $c)→ 在线" || fail "拉流程后仍离线(HTTP $c)"

# ---------------------------------------------------------------- 4. 节流
log "4. 节流:30 秒内第二次请求不写库"
age_phone $P1
app POST /api/v1/app/link "{\"boardMac\":\"$MAC\",\"deviceId\":\"$P1\",\"mode\":\"ble\"}" >/dev/null
[ "$(phone_field $P1 online)" = False ] && pass "30 秒内的请求被节流(没写库)" || fail "节流没生效"
# /app/phone 自己写 last_seen,不受节流影响
app POST /api/v1/app/phone "{\"deviceId\":\"$P1\",\"appVersion\":\"3.7\"}" >/dev/null
[ "$(phone_field $P1 online)" = True ] && pass "/app/phone 不受节流影响" || fail "/app/phone 后仍离线"
[ "$(phone_field $P1 appVersion)" = 3.7 ] && pass "版本号跟着更新成 3.7" || fail "版本号没更新"

# ---------------------------------------------------------------- 5. 边界
log "5. 边界:陌生 deviceId / token 不对"
n0=$(tmysql $DB -e "SELECT COUNT(*) FROM phones")
app POST /api/v1/app/link "{\"boardMac\":\"$MAC\",\"deviceId\":\"never-registered\",\"mode\":\"ble\"}" >/dev/null
n1=$(tmysql $DB -e "SELECT COUNT(*) FROM phones")
[ "$n0" = "$n1" ] && pass "陌生 deviceId 不会多出手机($n1 台)" || fail "手机数 $n0 → $n1"
wait_throttle; age_phone $P1
c=$(app POST /api/v1/app/link "{\"boardMac\":\"$MAC\",\"deviceId\":\"$P1\"}" bad-token)
[ "$c" = 401 ] && [ "$(phone_field $P1 online)" = False ] && pass "token 不对:401 且不刷新" \
  || fail "token 不对:HTTP $c online=$(phone_field $P1 online)"

grep -E "Error|错误|出错" "$TMP/srv.log" | grep -v "App token 不对" | head -3

echo
[ $FAILS = 0 ] && echo "全部通过 ✅" || echo "失败 $FAILS 项 ❌"
exit $FAILS
