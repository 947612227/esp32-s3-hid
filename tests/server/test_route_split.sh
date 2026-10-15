#!/usr/bin/env bash
# ============================================================================
#  优化11 验证:拆路由前后接口行为完全一致(在服务器上跑)
#
#  起临时 MySQL(端口 3407,独立数据目录,不碰正式库),分别对
#    旧服务端(拆分前的提交)和 新服务端(本分支 server/)
#  1. 导出路由表(方法 + 路径 + 中间件链,按匹配顺序)→ 逐行比对
#  2. 各用一个全新库启动,跑同一串请求(板子/App/流程/登录/后台/静态/鉴权失败)
#     → 状态码 + 响应体(去掉时间戳、token 等每次不同的值)逐条比对
#     拆分之后有意改动的行为登记在 EXPECTED_DIFFS 里,其余任何差异都算失败
#
#  用法(仓库根目录): bash tests/server/test_route_split.sh [旧版本 git 引用,默认 63bd4db]
#  ⚠️ bash 变量名只能用 ASCII,所以变量是英文 + 中文注释
# ============================================================================
set -u
REPO=$(cd "$(dirname "$0")/../.." && pwd)   # 仓库根目录
OLD_REF=${1:-63bd4db}                       # 旧服务端取自这个提交
PROD_DIR=/root/touchpad-server              # 只借用它的 node_modules(只读)
TMP=/tmp/tp-routesplit-test                 # 临时目录
MY_PORT=3407                                # 临时 MySQL 端口
HTTP_PORT=3997                              # 测试服务端口
T_USER=tp_test
T_PASS=tp_test_pw_$$
FAILS=0
SRV_PID=
MAC=aa:bb:cc:dd:ee:11                       # 测试用板子 MAC

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "  ❌ $*"; FAILS=$((FAILS + 1)); }
pass() { echo "  ✅ $*"; }

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
pass "临时实例已启动"

NEW_SRV="$REPO/server"
OLD_SRV="$TMP/old/server"
mkdir -p "$TMP/old"
(cd "$REPO" && git archive "$OLD_REF" server) | tar -x -C "$TMP/old" || { echo "取旧服务端失败"; exit 1; }
[ -e "$NEW_SRV/node_modules" ] || ln -s "$PROD_DIR/node_modules" "$NEW_SRV/node_modules"
ln -s "$PROD_DIR/node_modules" "$OLD_SRV/node_modules"
if [ ! -d "$NEW_SRV/data" ]; then mkdir -p "$NEW_SRV/data"; touch "$NEW_SRV/data/.created_by_test"; fi
mkdir -p "$OLD_SRV/data"

env_for() {   # $1 = 库名
  echo "PORT=$HTTP_PORT DB_HOST=127.0.0.1 DB_PORT=$MY_PORT DB_USER=$T_USER DB_PASS=$T_PASS DB_NAME=$1 ADMIN_BUILT=固定 ADMIN_VER=固定"
}

# ---------------------------------------------------------------- 1. 路由表
log "1. 路由表比对"
for side in old new; do
  dir=$OLD_SRV; [ $side = new ] && dir=$NEW_SRV
  (cd "$dir" && env $(env_for "rt_$side") node -r "$REPO/tests/server/dump_routes.js" index.js \
     > "$TMP/routes_$side.raw" 2> "$TMP/routes_$side.err")
  grep -E '^(路由|中间件) ' "$TMP/routes_$side.raw" > "$TMP/routes_$side.txt"   # 去掉启动时打印的账号密码等
  echo "  · $side: $(grep -c '^路由' "$TMP/routes_$side.txt") 条路由,$(grep -c '^中间件' "$TMP/routes_$side.txt") 个中间件"
done
# 拆分之后**有意新增**的路由(后台远程升级 / USB 刷机文件 在 admin.js;一键构建 src/routes/build.js)。
# 规则:旧路由一条不能少、不能变、顺序不能乱;新增的只能是这里登记的(按 diff 输出顺序)
cat > "$TMP/expected_new_routes.txt" <<'EOF'
路由 POST /api/admin/ota needLogin → needAdminRole → <anonymous>
路由 POST /api/admin/ota/cancel needLogin → needAdminRole → <anonymous>
路由 POST /api/admin/ota/auto needLogin → needAdminRole → <anonymous>
路由 GET /api/admin/flash/:src needLogin → <anonymous>
路由 GET /api/admin/flash/:src/:name needLogin → <anonymous>
路由 GET /api/admin/build/meta needLogin → <anonymous>
路由 GET /api/admin/builds needLogin → <anonymous>
路由 POST /api/admin/builds needLogin → needAdminRole → <anonymous>
路由 GET /api/admin/builds/:id needLogin → <anonymous>
路由 GET /api/admin/builds/:id/log needLogin → <anonymous>
路由 GET /api/admin/builds/:id/artifact needLogin → <anonymous>
路由 POST /api/admin/builds/:id/publish needLogin → needAdminRole → <anonymous>
路由 POST /api/admin/builds/:id/cancel needLogin → needAdminRole → <anonymous>
EOF
diff "$TMP/routes_old.txt" "$TMP/routes_new.txt" > "$TMP/routes.diff"
removed=$(grep -c '^<' "$TMP/routes.diff")
grep '^>' "$TMP/routes.diff" | sed 's/^> //' > "$TMP/routes_added.txt"
if [ -s "$TMP/routes_old.txt" ] && [ "$removed" = 0 ] && cmp -s "$TMP/routes_added.txt" "$TMP/expected_new_routes.txt"; then
  pass "旧路由逐行一致(方法、路径、中间件链、顺序),只多了登记的 $(wc -l < "$TMP/routes_added.txt") 条新接口"
else
  fail "路由表不一致(少/改了 $removed 条,新增和登记的不符):"; head -40 "$TMP/routes.diff"; cat "$TMP/routes_new.err" | head -20
fi

# ---------------------------------------------------------------- 2. 请求回放
start_server() {   # $1 = 服务端目录  $2 = 库名
  (cd "$1" && exec env $(env_for "$2") node index.js > "$TMP/server_$2.log" 2>&1) &
  SRV_PID=$!
  for i in $(seq 1 30); do
    curl -s "http://127.0.0.1:$HTTP_PORT/api/v1/board/health" | grep -q '"ok":true' && return 0
    sleep 0.5
  done
  echo "  服务没起来:"; tail -20 "$TMP/server_$2.log"; return 1
}
stop_server() { kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=; }

# 把每次都不一样的值抹掉:时间、毫秒时间戳、token、会话、耗时
normalize() {
  sed -E \
    -e "s/$BT/<板子token>/g; s/$AT/<Apptoken>/g" \
    -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}(:[0-9]{2}(\.[0-9]+)?Z?)?/<时间>/g' \
    -e 's/\b1[0-9]{12}\b/<毫秒>/g' \
    -e 's/tp_sess=[0-9a-f]+/tp_sess=<会话>/g; s/"token":"[0-9a-f]{32,}"/"token":"<会话>"/g' \
    -e 's/"(uptimeSec|uptime|ageSec|ago|secAgo|ms)":[0-9]+/"\1":<数>/g'
}

# 一条请求:方法 路径 [数据] [额外 curl 参数...] —— 记"方法 路径 → 状态码 + 响应体"
REQ_OUT=
req() {
  local m=$1 p=$2 d=${3:-}; shift 3 2>/dev/null || shift $#
  local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$m" "$@")
  [ -n "$d" ] && args+=(-H 'Content-Type: application/json' --data "$d")
  local code; code=$(curl "${args[@]}" "http://127.0.0.1:$HTTP_PORT$p")
  { echo "### $m $p → $code"; head -c 4000 "$TMP/body" | normalize; echo; } >> "$REQ_OUT"
}

replay() {   # $1 = old/new
  local side=$1 dir=$OLD_SRV; [ $side = new ] && dir=$NEW_SRV
  REQ_OUT="$TMP/replay_$side.txt"; : > "$REQ_OUT"
  start_server "$dir" "rp_$side" || { fail "$side 服务没起来"; return; }
  BT=$(tmysql -N -e "SELECT v FROM rp_$side.settings WHERE k='boardToken'")
  AT=$(tmysql -N -e "SELECT v FROM rp_$side.settings WHERE k='appToken'")
  local PASS; PASS=$(sed -n 's/.*密  码: //p' "$TMP/server_rp_$side.log" | head -1)
  local B=(-H "X-Token: $BT") A=(-H "X-Token: $AT") C=(-b "$TMP/cookie_$side" -c "$TMP/cookie_$side")

  # 鉴权失败
  req POST /api/v1/board/heartbeat '{"mac":"x"}'
  req GET  "/api/v1/app/flows?deviceId=d1"
  req GET  /api/admin/me
  req GET  /admin/ ''
  # 板子
  req GET  /api/v1/board/health
  req POST /api/v1/board/heartbeat '{}' "${B[@]}"
  req POST /api/v1/board/heartbeat "{\"mac\":\"$MAC\",\"fw\":\"1.0\",\"ip\":\"192.168.1.5\",\"flows\":2,\"flowNames\":[\"甲\",\"乙\"],\"flowTexts\":{\"甲\":\"tap 1 1\"}}" "${B[@]}"
  req POST /api/v1/board/heartbeat "{\"mac\":\"$MAC\",\"fw\":\"1.0\",\"flows\":2}" "${B[@]}"
  req POST /api/v1/board/event "{\"mac\":\"$MAC\",\"type\":\"flow_event\",\"flow\":\"甲\",\"message\":\"测试通知\"}" "${B[@]}"
  req POST /api/v1/board/event "{\"mac\":\"$MAC\"}" "${B[@]}"
  req GET  /api/v1/board/firmware/download/999 '' "${B[@]}"
  # App
  req GET  "/api/v1/app/version?code=1"
  req GET  /api/v1/app/download/999
  req POST /api/v1/app/phone '{"deviceId":"d1","name":"测试机","model":"S23","android":"14","appVersion":"1.0"}' "${A[@]}"
  req POST /api/v1/app/phone '{}' "${A[@]}"
  req POST /api/v1/app/apps '{"deviceId":"d1","apps":[{"pkg":"a.b","name":"甲"}]}' "${A[@]}"
  req POST /api/v1/app/link "{\"boardMac\":\"$MAC\",\"mode\":\"ble\",\"hid\":\"x\",\"deviceId\":\"d1\"}" "${A[@]}"
  req POST /api/v1/app/board-relay "{\"boardMac\":\"$MAC\",\"deviceId\":\"d1\",\"status\":{\"fw\":\"1.0\",\"flows\":1,\"flowNames\":[\"甲\"]}}" "${A[@]}"
  # 流程
  req POST /api/v1/app/flows '{"deviceId":"d1","name":"流程一","text":"tap 1 1","baseVer":0}' "${A[@]}"
  req POST /api/v1/app/flows '{"deviceId":"d1","name":"流程二","text":"wait 1"}' "${A[@]}"
  req POST /api/v1/app/flows '{"deviceId":"d1","name":"流程一","text":"tap 2 2","baseVer":1}' "${A[@]}"
  req POST /api/v1/app/flows '{"deviceId":"d1","name":"流程一","text":"冲突","baseVer":1}' "${A[@]}"
  req GET  "/api/v1/app/flows?deviceId=d1&since=0" '' "${A[@]}"
  req DELETE "/api/v1/app/flows/%E6%B5%81%E7%A8%8B%E4%BA%8C?deviceId=d1" '' "${A[@]}"
  req GET  "/api/v1/app/flows?deviceId=d2&since=3" '' "${A[@]}"
  req POST /api/v1/app/schedule "{\"boardMac\":\"$MAC\",\"deviceId\":\"d1\",\"tasks\":[{\"name\":\"流程一\",\"at\":\"08:00\"}]}" "${A[@]}"
  req GET  /api/v1/app/screenshot/request '' "${A[@]}"
  req POST /api/v1/app/screenshot '{}' "${A[@]}"
  # 登录
  req GET  /admin/login
  req POST /admin/login '' -d "username=admin&password=错的"
  sleep 1.1   # 审计日志按 at(秒)倒序,同一秒的两条顺序不确定 —— 隔开一秒才好逐字比对
  req POST /admin/login '' -d "username=admin&password=$PASS" "${C[@]}"
  # 后台
  for p in /api/admin/me /api/admin/summary /api/admin/boards /api/admin/event-stats \
           /api/admin/events /api/admin/boards_old "/api/admin/board/$MAC" /api/admin/phones \
           /api/admin/firmware /api/admin/apk /api/admin/users /api/admin/sessions /api/admin/audit \
           /api/admin/flows "/api/admin/flows?owner=d1" /api/admin/flow-backup; do
    req GET "$p" '' "${C[@]}"
  done
  req POST /api/admin/flows/status '{"owner":"d1","name":"流程一","status":"disabled"}' "${C[@]}"
  req POST /api/admin/led "{\"mac\":\"$MAC\",\"on\":false}" "${C[@]}"
  req POST /api/admin/policy "{\"mac\":\"$MAC\",\"mode\":\"deny\"}" "${C[@]}"
  req POST /api/admin/users '{"username":"u2","password":"p2pass88","role":"viewer","name":"二号"}' "${C[@]}"
  req GET  /api/admin/users '' "${C[@]}"
  req GET  /admin/ '' "${C[@]}"
  req GET  /admin '' "${C[@]}"
  req GET  /admin/api/me '' "${C[@]}"
  req GET  /admin/logout '' "${C[@]}"
  req GET  /api/admin/me '' "${C[@]}"
  # 静态
  req GET  /favicon.ico
  req GET  /favicon.svg
  req GET  /
  req GET  /download/不存在.bin
  req GET  /admin/static/不存在.js
  req GET  /不存在的路径
  stop_server
  echo "  · $side: 回放 $(grep -c '^### ' "$REQ_OUT") 条请求"
}

log "2. 请求回放比对"
replay old
replay new
# 拆分之后**有意**改动的行为(不是拆分引入的),逐条登记:请求 | 旧响应 | 新响应 | 原因
#   不在这张表里的差异一律算失败;表里登记了但没出现的差异也报出来
EXPECTED_DIFFS="$TMP/expected_diffs.txt"
cat > "$EXPECTED_DIFFS" <<'EOF'
### POST /api/v1/board/heartbeat → 200|{"ok":true,"serverTime":<毫秒>,"firmware":{"hasUpdate":false},"needFlows":false}|{"ok":true,"serverTime":<毫秒>,"firmware":{"hasUpdate":false},"needFlows":true}|优化17:上一条心跳的「乙」只有名字没内容(留底 NULL)⇒ 要求板子补报全量
### GET /api/admin/boards → 200|{"ok":true,"boards":[{"mac":"aa:bb:cc:dd:ee:11","name":"","fw":"1.0","ip":"","channel":"stable","heap":0,"uptime":<数>,"hid":false,"flows":0,"running":"","flowNames":["乙","甲"],"next":null,"wifi":{"ssid":"","rssi":0,"channel":0,"hasPass":false},"firstSeen":<毫秒>,"lastSeen":<毫秒>,"online":true,"pubIp":"","link":{"mode":"ble","hid":"x","at":<毫秒>},"geo":null}]}|{"ok":true,"boards":[{"mac":"aa:bb:cc:dd:ee:11","name":"","fw":"1.0","ip":"","channel":"stable","heap":0,"uptime":<数>,"hid":false,"flows":0,"running":"","remoteOta":false,"ota":{"auto":false,"state":"","stateText":"","msg":"","at":0},"flowNames":["乙","甲"],"next":null,"wifi":{"ssid":"","rssi":0,"channel":0,"hasPass":false},"firstSeen":<毫秒>,"lastSeen":<毫秒>,"online":true,"pubIp":"","link":{"mode":"ble","hid":"x","at":<毫秒>},"geo":null}]}|远程升级:板子列表多了 remoteOta(固件支不支持)和 ota(升级状态 / 自动升级开关)
### GET /api/admin/board/aa:bb:cc:dd:ee:11 → 200|{"ok":true,"policy":null,"board":{"mac":"aa:bb:cc:dd:ee:11","name":"","fw":"1.0","ip":"","channel":"stable","heap":0,"uptime":<数>,"hid":0,"flows":0,"running":"","next_name":"","next_at":0,"wifi_ssid":"","wifi_rssi":0,"wifi_channel":0,"wifi_pass_enc":null,"first_seen":"<时间>","last_seen":"<时间>","pub_ip":null,"geo_country":null,"geo_region":null,"geo_city":null,"geo_isp":null,"geo_lat":null,"geo_lon":null,"geo_at":null,"link_mode":"ble","link_hid":"x","link_by":"d1","link_at":"<时间>","online":true,"wifi":{"ssid":"","rssi":0,"channel":0,"pass":""},"ips":[{"ip":"192.168.1.5","at":"<时间>"}],"heartbeats":[{"ts":"<时间>","heap":0,"uptime":<数>,"hid":0,"flows":0,"running":""},{"ts":"<时间>","heap":0,"uptime":<数>,"hid":0,"flows":2,"running":""},{"ts":"<时间>","heap":0,"uptime":<数>,"hid":0,"flows":2,"running":""}]}}|{"ok":true,"policy":null,"ota":{"auto":false,"state":"","stateText":"","msg":"","at":0},"board":{"mac":"aa:bb:cc:dd:ee:11","name":"","fw":"1.0","ip":"","channel":"stable","heap":0,"uptime":<数>,"hid":0,"flows":0,"running":"","next_name":"","next_at":0,"wifi_ssid":"","wifi_rssi":0,"wifi_channel":0,"wifi_pass_enc":null,"first_seen":"<时间>","last_seen":"<时间>","pub_ip":null,"geo_country":null,"geo_region":null,"geo_city":null,"geo_isp":null,"geo_lat":null,"geo_lon":null,"geo_at":null,"link_mode":"ble","link_hid":"x","link_by":"d1","link_at":"<时间>","caps":"","online":true,"remoteOta":false,"wifi":{"ssid":"","rssi":0,"channel":0,"pass":""},"ips":[{"ip":"192.168.1.5","at":"<时间>"}],"heartbeats":[{"ts":"<时间>","heap":0,"uptime":<数>,"hid":0,"flows":0,"running":""},{"ts":"<时间>","heap":0,"uptime":<数>,"hid":0,"flows":2,"running":""},{"ts":"<时间>","heap":0,"uptime":<数>,"hid":0,"flows":2,"running":""}]}}|远程升级:板子详情多了 ota、remoteOta,以及 boards 新列 caps
EOF
python3 - "$TMP/replay_old.txt" "$TMP/replay_new.txt" "$EXPECTED_DIFFS" > "$TMP/replay.cmp" <<'PY'
import sys, io
# 按 "### " 开头切成一条条请求记录:(请求行, 响应体)
def records(path):
    out, cur = [], None
    for line in io.open(path, encoding="utf-8").read().split("\n"):
        if line.startswith("### "):
            cur = [line, []]; out.append(cur)
        elif cur is not None:
            cur[1].append(line)
    return [(h, "\n".join(b).strip()) for h, b in out]
old, new = records(sys.argv[1]), records(sys.argv[2])
expected = []
for line in io.open(sys.argv[3], encoding="utf-8"):
    if line.strip():
        expected.append(tuple(line.rstrip("\n").split("|", 3)))
used, bad = set(), 0
if len(old) != len(new):
    print("BAD 请求条数不同:旧 %d / 新 %d" % (len(old), len(new))); bad += 1
for i, (o, n) in enumerate(zip(old, new)):
    if o == n:
        continue
    hit = [k for k, e in enumerate(expected) if e[0] == o[0] == n[0] and e[1] == o[1] and e[2] == n[1]]
    if hit:
        used.add(hit[0]); print("EXP 第 %d 条 %s:%s" % (i + 1, o[0][4:], expected[hit[0]][3]))
    else:
        bad += 1; print("BAD 第 %d 条\n  旧: %s %s\n  新: %s %s" % (i + 1, o[0], o[1][:300], n[0], n[1][:300]))
for k, e in enumerate(expected):
    if k not in used:
        bad += 1; print("BAD 登记的预期差异没有出现(表该更新了):%s" % e[3])
print("SUM %d %d" % (len(new), bad))
PY
read -r _ n_total n_bad < <(grep '^SUM ' "$TMP/replay.cmp")
grep '^EXP ' "$TMP/replay.cmp" | sed 's/^EXP /  · 预期差异:/'
if [ "${n_bad:-1}" = 0 ]; then
  pass "${n_total} 条请求的状态码和响应体一致(登记的预期差异除外)"
else
  fail "回放结果不一致:"; grep -v '^SUM \|^EXP ' "$TMP/replay.cmp" | sed 's/^BAD /  /' | head -60
fi
# 状态码分布(确认回放真的打到了各种分支,不是全 404)
echo "  · 状态码分布:$(grep -o '→ [0-9]*' "$TMP/replay_new.txt" | sort | uniq -c | tr '\n' ' ')"
# 程序级异常(变量未定义、调用不存在的函数、未捕获异常):旧服务端也没有,新的就不该有
ERR_RE='TypeError|ReferenceError|SyntaxError|未捕获'
n_old=$(grep -cE "$ERR_RE" "$TMP/server_rp_old.log"); n_new=$(grep -cE "$ERR_RE" "$TMP/server_rp_new.log")
if [ "$n_new" -le "$n_old" ]; then pass "新服务端没有新增程序异常(旧 $n_old / 新 $n_new)"
else fail "新服务端多了程序异常:"; grep -nE "$ERR_RE" "$TMP/server_rp_new.log" | head -5; fi
echo "  · 新服务端日志里的 error 行(业务错误,两边应一致):"
grep -iE "error|出错" "$TMP/server_rp_new.log" | sed 's/^/      /' | head -8

echo
[ $FAILS -eq 0 ] && echo "全部通过" || echo "失败 $FAILS 项"
exit $FAILS
