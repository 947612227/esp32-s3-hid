#!/usr/bin/env bash
# ============================================================================
#  版本保留策略验证:APK、固件各只保留最近 5 个版本(在服务器上跑)
#
#  起临时 MySQL(端口 3407,独立数据目录,不碰正式库),用本分支 server/:
#    1. APK 依次上传 versionCode 1~7(6 传两次):每次上传后自动清理
#       ⇒ 最后只剩 7、6(较新那次)、5、4、3;删掉的记录对应的文件也没了
#    2. 「检查更新」照常给最新版,最新版能下载
#    3. 固件:策略指定了 1.0.0 之后再连传 stable 1.0.1~1.0.6 + beta 2 个
#       ⇒ stable 留最新 5 个 + 被指定的 1.0.0;beta 不受 stable 影响;板子心跳照常拿到最新版
#    4. 库里直接塞进旧版本(模拟升级前的历史数据)⇒ 重启服务时自动清掉
#    5. 审计日志有 prune_apk / prune_firmware 记录
#
#  用法(仓库根目录): bash tests/server/test_version_prune.sh
#  ⚠️ bash 变量名只能用 ASCII,所以变量是英文 + 中文注释
# ============================================================================
set -u
REPO=$(cd "$(dirname "$0")/../.." && pwd)   # 仓库根目录
PROD_DIR=/root/touchpad-server              # 只借用它的 node_modules(只读)
TMP=/tmp/tp-prune-test                      # 临时目录
MY_PORT=3407                                # 临时 MySQL 端口
HTTP_PORT=3995                              # 测试服务端口
T_USER=tp_test
T_PASS=tp_test_pw_$$
DB=tp_prune                                 # 测试库名
FAILS=0
SRV_PID=
SRV="$REPO/server"
URL="http://127.0.0.1:$HTTP_PORT"

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "  ❌ $*"; FAILS=$((FAILS + 1)); }
pass() { echo "  ✅ $*"; }
info() { echo "  ·  $*"; }

cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null
  if [ -f "$TMP/mysqld.pid" ]; then kill "$(cat "$TMP/mysqld.pid")" 2>/dev/null; sleep 2; fi
  rm -rf "$TMP"
  [ -L "$SRV/node_modules" ] && rm -f "$SRV/node_modules"
  [ -f "$SRV/data/.created_by_test" ] && rm -rf "$SRV/data"
}
trap cleanup EXIT

tmysql() { mysql --no-defaults -h127.0.0.1 -P$MY_PORT -uroot -N "$@"; }

# ---------------------------------------------------------------- 0. 准备
log "0. 起临时 MySQL 实例(端口 $MY_PORT)"
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
pass "临时实例已启动"

start_server() {   # $1 = 日志名
  (cd "$SRV" && PORT=$HTTP_PORT DB_HOST=127.0.0.1 DB_PORT=$MY_PORT DB_USER=$T_USER \
    DB_PASS=$T_PASS DB_NAME=$DB exec node index.js > "$TMP/$1.log" 2>&1) &
  SRV_PID=$!
  for i in $(seq 1 30); do
    curl -s "$URL/api/v1/board/health" | grep -q '"ok":true' && return 0
    sleep 0.5
  done
  echo "  服务没起来:"; tail -20 "$TMP/$1.log"; return 1
}
stop_server() { kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=; }

up_apk() {   # $1 = versionCode → 打印响应
  head -c $((1000 + $1)) /dev/urandom > "$TMP/a.apk"
  curl -s -b "$TMP/ck" -X POST "$URL/api/admin/apk" \
    -F "file=@$TMP/a.apk" -F "versionCode=$1" -F "versionName=v$1" -F "notes=测试$1"
  echo
}
up_fw() {    # $1 = 版本  $2 = 渠道 → 打印响应
  head -c 2000 /dev/urandom > "$TMP/f.bin"
  curl -s -b "$TMP/ck" -X POST "$URL/api/admin/firmware" \
    -F "file=@$TMP/f.bin" -F "version=$1" -F "channel=$2" -F "notes=测试"
  echo
  sleep 1.1   # 固件按 created_at(秒)排新旧 —— 隔开一秒,顺序才确定
}
apk_codes()  { tmysql $DB -e "SELECT GROUP_CONCAT(version_code ORDER BY version_code DESC, created_at DESC) FROM apks"; }
fw_vers()    { tmysql $DB -e "SELECT GROUP_CONCAT(version ORDER BY created_at DESC, id DESC) FROM firmware WHERE channel='$1'"; }
# 库里记录的文件 和 磁盘上的文件 是否一一对应
files_match() {   # $1 = 表  $2 = 目录
  diff <(tmysql $DB -e "SELECT file FROM $1" | sort) <(ls "$SRV/data/$2" 2>/dev/null | sort) > /dev/null
}

# ---------------------------------------------------------------- 1. APK 上传后自动清理
log "1. APK 依次上传 1~7(6 传两次)"
start_server s1 || exit 1
ADMIN_PASS=$(sed -n 's/.*密  码: //p' "$TMP/s1.log" | head -1)
curl -s -c "$TMP/ck" -o /dev/null -X POST "$URL/admin/login" -d "username=admin&password=$ADMIN_PASS"
grep -q tp_sess "$TMP/ck" && pass "管理员登录" || { fail "登录失败"; exit 1; }
for c in 1 2 3 4 5 6; do up_apk $c > /dev/null; done
first6=$(tmysql $DB -e "SELECT id FROM apks WHERE version_code=6")
sleep 1.1
r=$(up_apk 6)
info "第二次传 6 的响应:$r"
r=$(up_apk 7)
info "传 7 的响应:$r"
echo "$r" | grep -q '"pruned"' && pass "上传响应里带了清理结果" || fail "响应里没有 pruned:$r"
[ "$(apk_codes)" = "7,6,5,4,3" ] && pass "只剩最近 5 个版本:$(apk_codes)" || fail "剩下的版本:$(apk_codes)"
[ -z "$(tmysql $DB -e "SELECT id FROM apks WHERE id='$first6'")" ] \
  && pass "6 传了两次:较早那次被删,留最新那次" || fail "较早那次 6 还在"
files_match apks apk && pass "磁盘上的 APK 文件和记录一一对应($(ls "$SRV/data/apk" | wc -l) 个)" \
  || fail "文件和记录对不上:库 $(tmysql $DB -e 'SELECT COUNT(*) FROM apks') / 盘 $(ls "$SRV/data/apk" | wc -l)"

# ---------------------------------------------------------------- 2. 检查更新 / 下载照常
log "2. App 检查更新"
AT=$(tmysql $DB -e "SELECT v FROM settings WHERE k='appToken'")
v=$(curl -s "$URL/api/v1/app/version?code=3" -H "X-Token: $AT")
echo "$v" | grep -q '"hasUpdate":true' && echo "$v" | grep -q '"versionCode":7' \
  && pass "旧版本检查更新 → 最新 7" || fail "检查更新:$v"
dl=$(echo "$v" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p' | sed "s#http://[^/]*#$URL#")
code=$(curl -s -o "$TMP/dl.apk" -w '%{http_code}' "$dl" -H "X-Token: $AT")
[ "$code" = 200 ] && [ "$(stat -c%s "$TMP/dl.apk")" = 1007 ] && pass "最新版能下载(1007 字节)" \
  || fail "下载:HTTP $code,$(stat -c%s "$TMP/dl.apk" 2>/dev/null) 字节"

# ---------------------------------------------------------------- 3. 固件
log "3. 固件:策略指定 1.0.0 后再连传"
up_fw 1.0.0 stable > /dev/null
pin_id=$(tmysql $DB -e "SELECT id FROM firmware WHERE version='1.0.0'")
r=$(curl -s -b "$TMP/ck" -X POST "$URL/api/admin/policy" -H 'Content-Type: application/json' \
  -d "{\"mac\":\"aa:bb:cc:dd:ee:77\",\"mode\":\"allow\",\"target\":\"$pin_id\"}")
info "设策略:$r"
for v in 1.0.1 1.0.2 1.0.3 1.0.4 1.0.5 1.0.6; do up_fw $v stable > /dev/null; done
up_fw 2.0.0 beta > /dev/null; up_fw 2.0.1 beta > /dev/null
[ "$(fw_vers stable)" = "1.0.6,1.0.5,1.0.4,1.0.3,1.0.2,1.0.0" ] \
  && pass "stable 留最新 5 个 + 被策略指定的 1.0.0:$(fw_vers stable)" || fail "stable 剩下:$(fw_vers stable)"
[ "$(fw_vers beta)" = "2.0.1,2.0.0" ] && pass "beta 单独算,不受 stable 影响:$(fw_vers beta)" \
  || fail "beta 剩下:$(fw_vers beta)"
files_match firmware firmware && pass "磁盘上的固件文件和记录一一对应" || fail "固件文件和记录对不上"
BT=$(tmysql $DB -e "SELECT v FROM settings WHERE k='boardToken'")
hb=$(curl -s -X POST "$URL/api/v1/board/heartbeat" -H "X-Token: $BT" -H 'Content-Type: application/json' \
  -d '{"mac":"aa:bb:cc:dd:ee:01","fw":"1.0.2","flows":0}')
echo "$hb" | grep -q '"version":"1.0.6"' && pass "普通板子心跳拿到最新 1.0.6" || fail "心跳:$hb"
hb=$(curl -s -X POST "$URL/api/v1/board/heartbeat" -H "X-Token: $BT" -H 'Content-Type: application/json' \
  -d '{"mac":"aa:bb:cc:dd:ee:77","fw":"1.0.6","flows":0}')
echo "$hb" | grep -q '"version":"1.0.0"' && pass "被指定的板子照样拿到 1.0.0" || fail "指定板子心跳:$hb"
stop_server

# ---------------------------------------------------------------- 4. 启动时清理历史数据
log "4. 库里塞旧版本,重启时自动清理"
for c in 0 1 2; do
  f="old-$c.apk"; echo x > "$SRV/data/apk/$f"
  tmysql $DB -e "INSERT INTO apks (version_code, version_name, file, size, sha256) VALUES ($c,'old','$f',2,'')"
done
info "重启前:$(apk_codes)"
start_server s2 || exit 1
grep "\[保留策略\]" "$TMP/s2.log" | sed 's/^/  /'
[ "$(apk_codes)" = "7,6,5,4,3" ] && pass "重启后自动清掉:$(apk_codes)" || fail "重启后:$(apk_codes)"
files_match apks apk && pass "对应文件也删了" || fail "文件和记录对不上"
stop_server
start_server s3 || exit 1
grep -q "\[保留策略\]" "$TMP/s3.log" && fail "没有可清的也打了清理日志" || pass "没有多余版本时重启不做任何事"
stop_server

# ---------------------------------------------------------------- 5. 审计 / 日志
log "5. 审计与日志"
na=$(tmysql $DB -e "SELECT COUNT(*) FROM audit_log WHERE action='prune_apk'")
nf=$(tmysql $DB -e "SELECT COUNT(*) FROM audit_log WHERE action='prune_firmware'")
[ "$na" = 6 ] && pass "审计:prune_apk $na 条(1、2、旧 6、0、1、2)" || fail "prune_apk $na 条,应 6"
[ "$nf" = 1 ] && pass "审计:prune_firmware $nf 条(1.0.1)" || fail "prune_firmware $nf 条,应 1"
if grep -qE "TypeError|ReferenceError|清理失败|ER_" "$TMP"/s*.log; then
  fail "服务日志有异常:"; grep -nE "TypeError|ReferenceError|清理失败|ER_" "$TMP"/s*.log | head -3
else pass "服务日志无异常"; fi

echo
[ $FAILS -eq 0 ] && echo "全部通过" || echo "失败 $FAILS 项"
exit $FAILS
