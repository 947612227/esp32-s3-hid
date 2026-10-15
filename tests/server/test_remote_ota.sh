#!/usr/bin/env bash
# ============================================================================
#  后台远程升级 + 自动升级 + USB 刷机文件 验证(在服务器上跑)
#
#  起临时 MySQL(端口 3410)+ 测试服务(端口 3994),用本分支 server/,不碰正式库。
#  板子用 curl 模拟心跳:
#    1. 心跳回复的 firmware 段格式(hasUpdate/url/sha256),不会提示降级
#    2. 老固件(没有 ota-remote 能力)⇒ 手动命令直接失败、说明怎么办
#    3. 手动升级:跑流程时推迟 → 空闲下发(sha256/size 对)→ 重发不刷新时间 → 新版本心跳 ⇒ 成功
#    4. 板子报失败 / 报推迟
#    5. 15 分钟内有定时任务 ⇒ 推迟;下发后 10 分钟没结果 ⇒ 失败
#    6. 取消 / 永不升级策略 / 已是该版本
#    7. 自动升级:新版本自动下发、失败过的版本不再重试、出了更新的版本继续升、关掉就不升
#    8. App 代报不会把板子的能力清掉;后台列表/详情带升级状态
#    9. USB 刷机文件:构建产物 4 段可下载、发布后跟着固件走、删固件/清理旧版本时一起删、
#       手工上传的固件没有整片文件 ⇒ 409、没登录 ⇒ 401
#
#  用法(仓库根目录): bash tests/server/test_remote_ota.sh
#  ⚠️ bash 变量名只能用 ASCII,所以变量是英文 + 中文注释
# ============================================================================
set -u
REPO=$(cd "$(dirname "$0")/../.." && pwd)   # 仓库根目录
PROD_DIR=/root/touchpad-server              # 只借用它的 node_modules(只读)
TMP=/tmp/tp-ota-test                        # 临时目录
MY_PORT=3410                                # 临时 MySQL 端口
HTTP_PORT=3994                              # 测试服务端口
T_USER=tp_test
T_PASS=tp_test_pw_$$
DB=tp_ota                                   # 测试库名
FAILS=0
SRV_PID=
SRV="$REPO/server"
URL="http://127.0.0.1:$HTTP_PORT"
# 测试板子 MAC
M_OLD=aa:bb:cc:00:10:01     # 老固件(不支持远程)
M_OK=aa:bb:cc:00:10:02      # 正常升级
M_FAIL=aa:bb:cc:00:10:03    # 报失败 / 报推迟
M_SCHED=aa:bb:cc:00:10:04   # 定时任务 / 超时
M_CANCEL=aa:bb:cc:00:10:05  # 取消 / 策略
M_AUTO=aa:bb:cc:00:10:06    # 自动升级
M_AUTOOFF=aa:bb:cc:00:10:07 # 自动升级关
M_AUTOOLD=aa:bb:cc:00:10:08 # 自动升级开但固件太老

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

# 板子心跳:$1 MAC  $2 固件版本  $3 能力(空=老固件)  $4 额外 JSON 字段(可省,如 "running":"抢票")
# 回复存到 $TMP/hb.json
hb() {
  local extra=${4:-}
  local caps=
  [ -n "$3" ] && caps=",\"caps\":\"$3\""
  curl -s -o "$TMP/hb.json" -X POST "$URL/api/v1/board/heartbeat" -H "X-Token: $BT" \
    -H 'Content-Type: application/json' \
    -d "{\"mac\":\"$1\",\"fw\":\"$2\",\"heap\":100000,\"uptime\":10,\"flows\":0${caps}${extra:+,$extra}}"
}
# 后台 JSON 请求:$1 方法  $2 路径  $3 JSON  ⇒ 回复存到 $TMP/adm.json
adm() {
  curl -s -o "$TMP/adm.json" -b "$TMP/ck" -X "$1" "$URL$2" -H 'Content-Type: application/json' -d "$3"
}
# board_ota 某列:$1 MAC  $2 列名
ota_col() { tmysql $DB -e "SELECT $2 FROM board_ota WHERE mac='$1'"; }
# 后台下发升级:$1 MAC  $2 固件 id
ota_req() { adm POST /api/admin/ota "{\"macs\":[\"$1\"],\"fwId\":$2}"; }
# 上传固件:$1 版本  $2 渠道 ⇒ 输出固件 id
upload_fw() {
  head -c 200000 /dev/urandom > "$TMP/fw-$1.bin"
  curl -s -b "$TMP/ck" -X POST "$URL/api/admin/firmware" -F "file=@$TMP/fw-$1.bin" \
    -F "version=$1" -F "channel=$2" -F "notes=测试 $1" -o "$TMP/up.json"
  jq_ "$TMP/up.json" "d['id']"
}

# ---------------------------------------------------------------- 1. 心跳 firmware 段
log "1. 心跳回复的 firmware 段"
FW_A=$(upload_fw 1.3.0 stable)
FW_B=$(upload_fw 1.4.0 stable)
SHA_B=$(sha256sum "$TMP/fw-1.4.0.bin" | cut -d' ' -f1)
SIZE_B=$(stat -c %s "$TMP/fw-1.4.0.bin")
[ -n "$FW_A" ] && [ -n "$FW_B" ] && pass "上传两版固件 1.3.0(#$FW_A)/ 1.4.0(#$FW_B)" || fail "上传固件失败"
hb $M_OLD 1.3.0 ""
eq "$(jq_ $TMP/hb.json "d['firmware']['hasUpdate']")" True "1.3.0 的板子:hasUpdate=true"
eq "$(jq_ $TMP/hb.json "d['firmware']['version']")" 1.4.0 "提示的版本是 1.4.0"
has "$(jq_ $TMP/hb.json "d['firmware']['url']")" "/api/v1/board/firmware/download/$FW_B" "带下载地址"
eq "$(jq_ $TMP/hb.json "d['firmware']['sha256']")" "$SHA_B" "带 sha256"
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "没有后台命令时不带 ota 段"
hb $M_AUTOOFF 1.5.0 ota-remote
eq "$(jq_ $TMP/hb.json "d['firmware']['hasUpdate']")" False "板子 1.5.0 比渠道最新 1.4.0 还新:不提示降级"

# ---------------------------------------------------------------- 2. 老固件
log "2. 老固件收不到命令 ⇒ 手动命令直接失败"
ota_req $M_OLD $FW_B
eq "$(jq_ $TMP/adm.json "d['queued']")" 1 "下发接口:排队 1 块"
eq "$(ota_col $M_OLD state)" pending "状态 pending(等心跳)"
hb $M_OLD 1.3.0 ""
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "老固件心跳不带 ota 段"
eq "$(ota_col $M_OLD state)" failed "状态 failed"
has "$(ota_col $M_OLD state_msg)" "USB 刷机" "失败原因说明了怎么办"

# ---------------------------------------------------------------- 3. 手动升级成功
log "3. 手动升级:忙时推迟 → 空闲下发 → 成功"
hb $M_OK 1.3.0 ota-remote
eq "$(tmysql $DB -e "SELECT caps FROM boards WHERE mac='$M_OK'")" ota-remote "心跳把能力 ota-remote 记进 boards.caps"
ota_req $M_OK $FW_B
hb $M_OK 1.3.0 ota-remote '"running":"抢票流程"'
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "正在跑流程:不下发"
eq "$(ota_col $M_OK state)" deferred "状态 deferred"
has "$(ota_col $M_OK state_msg)" "抢票流程" "推迟原因写了流程名"
hb $M_OK 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "d['ota']['version']")" 1.4.0 "空闲:下发 1.4.0"
eq "$(jq_ $TMP/hb.json "d['ota']['sha256']")" "$SHA_B" "命令里 sha256 和文件一致"
eq "$(jq_ $TMP/hb.json "d['ota']['size']")" "$SIZE_B" "命令里 size 和文件一致"
has "$(jq_ $TMP/hb.json "d['ota']['url']")" "/download/$FW_B" "命令里带下载地址"
# 板子解析 firmware 段用的是全文第一个 version,ota 必须排在 firmware 后面
python3 -c "import sys;t=open(sys.argv[1]).read();sys.exit(0 if t.index('\"firmware\"')<t.index('\"ota\"') else 1)" "$TMP/hb.json" \
  && pass "回复里 ota 段排在 firmware 段后面" || fail "ota 段排在 firmware 前面了"
DL=$(curl -s -H "X-Token: $BT" "$(jq_ $TMP/hb.json "d['ota']['url']")" | sha256sum | cut -d' ' -f1)
eq "$DL" "$SHA_B" "按命令里的地址下载,sha256 对得上"
eq "$(ota_col $M_OK state)" sent "状态 sent"
T1=$(ota_col $M_OK "UNIX_TIMESTAMP(state_at)")
sleep 1.2
hb $M_OK 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "d['ota']['version']")" 1.4.0 "还没升完:再带一次命令"
eq "$(ota_col $M_OK "UNIX_TIMESTAMP(state_at)")" "$T1" "重发不刷新时间(10 分钟超时才算得准)"
hb $M_OK 1.4.0 ota-remote
eq "$(ota_col $M_OK state)" success "报 1.4.0:状态 success"
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "成功后不再带命令"
eq "$(ota_col $M_OK "IFNULL(want_fw_id,'空')")" 空 "命令已清掉"

# ---------------------------------------------------------------- 4. 板子报失败 / 推迟
log "4. 板子报失败 / 报推迟"
hb $M_FAIL 1.3.0 ota-remote
ota_req $M_FAIL $FW_B
hb $M_FAIL 1.3.0 ota-remote
eq "$(ota_col $M_FAIL state)" sent "下发"
hb $M_FAIL 1.3.0 ota-remote '"otaReport":{"state":"failed","ver":"1.4.0","msg":"sha256 不一致"}'
eq "$(ota_col $M_FAIL state)" failed "板子报失败:状态 failed"
has "$(ota_col $M_FAIL state_msg)" "sha256 不一致" "失败原因是板子报的"
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "报失败的这次不再带命令"
hb $M_FAIL 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "之后也不再自己重试(手动的要重新点)"
ota_req $M_FAIL $FW_B
hb $M_FAIL 1.3.0 ota-remote
hb $M_FAIL 1.3.0 ota-remote '"running":"流程B","otaReport":{"state":"deferred","ver":"1.4.0","msg":"正在执行流程"}'
eq "$(ota_col $M_FAIL state)" deferred "板子报推迟(还在跑):状态 deferred"
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "还在跑:不下发"
hb $M_FAIL 1.3.0 ota-remote
eq "$(ota_col $M_FAIL state)" sent "跑完了:再下发"
hb $M_FAIL 1.3.0 ota-remote '"otaReport":{"state":"deferred","ver":"1.4.0","msg":"刚才在跑"}'
eq "$(jq_ $TMP/hb.json "'ota' in d")" True "报推迟的同一次心跳板子已空闲:立即再下发"
eq "$(ota_col $M_FAIL state)" sent "状态回到 sent"

# ---------------------------------------------------------------- 5. 定时任务 / 超时
log "5. 15 分钟内有定时任务 ⇒ 推迟;下发 10 分钟没结果 ⇒ 失败"
hb $M_SCHED 1.3.0 ota-remote
tmysql $DB -e "INSERT INTO schedules (board_mac, flow_name, fire_at, mode, enabled) VALUES ('$M_SCHED','定时抢票',NOW() + INTERVAL 5 MINUTE,'at',1)"
ota_req $M_SCHED $FW_B
hb $M_SCHED 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "5 分钟后有定时任务:不下发"
has "$(ota_col $M_SCHED state_msg)" "定时抢票" "推迟原因写了定时任务名"
tmysql $DB -e "UPDATE schedules SET enabled = 0 WHERE board_mac='$M_SCHED'"
hb $M_SCHED 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "'ota' in d")" True "定时任务停用后:下发"
tmysql $DB -e "UPDATE schedules SET enabled = 1, fire_at = NOW() + INTERVAL 30 MINUTE WHERE board_mac='$M_SCHED'"
tmysql $DB -e "UPDATE board_ota SET state='pending' WHERE mac='$M_SCHED'"
hb $M_SCHED 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "'ota' in d")" True "定时任务在 30 分钟后:不挡"
tmysql $DB -e "UPDATE board_ota SET state_at = NOW() - INTERVAL 11 MINUTE WHERE mac='$M_SCHED'"
hb $M_SCHED 1.3.0 ota-remote
eq "$(ota_col $M_SCHED state)" failed "下发 11 分钟还是旧版本:判失败"
has "$(ota_col $M_SCHED state_msg)" "10 分钟" "失败原因写明超时"

# ---------------------------------------------------------------- 6. 取消 / 策略 / 已是该版本
log "6. 取消 / 永不升级策略 / 已是该版本"
hb $M_CANCEL 1.3.0 ota-remote
ota_req $M_CANCEL $FW_B
adm POST /api/admin/ota/cancel "{\"mac\":\"$M_CANCEL\"}"
eq "$(jq_ $TMP/adm.json "d['cancelled']")" True "取消成功"
hb $M_CANCEL 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "取消后心跳不带命令"
eq "$(ota_col $M_CANCEL state)" cancelled "状态 cancelled"
adm POST /api/admin/ota/cancel "{\"mac\":\"$M_CANCEL\"}"
eq "$(jq_ $TMP/adm.json "d['cancelled']")" False "没有待执行的:再取消返回 false"
adm POST /api/admin/policy "{\"mac\":\"$M_CANCEL\",\"mode\":\"deny\",\"target\":null}"
ota_req $M_CANCEL $FW_B
eq "$(jq_ $TMP/adm.json "d['queued']")" 0 "策略「永不升级」:不排队"
has "$(jq_ $TMP/adm.json "d['results'][0]['message']")" "永不升级" "说明了原因"
adm POST /api/admin/policy "{\"mac\":\"$M_CANCEL\",\"mode\":\"none\",\"target\":null}"
ota_req $M_OK $FW_B
has "$(jq_ $TMP/adm.json "d['results'][0]['message']")" "已经是 1.4.0" "已是 1.4.0 的板子:不排队"
ota_req aa:bb:cc:99:99:99 $FW_B
has "$(jq_ $TMP/adm.json "d['results'][0]['message']")" "没有这块板子" "不存在的板子:报错"
adm POST /api/admin/ota "{\"macs\":[\"$M_CANCEL\"],\"fwId\":999999}"
eq "$(jq_ $TMP/adm.json "d['ok']")" False "不存在的固件:报错"

# ---------------------------------------------------------------- 7. 自动升级
log "7. 自动升级"
hb $M_AUTO 1.3.0 ota-remote
hb $M_AUTOOFF 1.3.0 ota-remote
hb $M_AUTOOLD 1.3.0 ""
adm POST /api/admin/ota/auto "{\"macs\":[\"$M_AUTO\",\"$M_AUTOOLD\"],\"auto\":true}"
eq "$(ota_col $M_AUTO auto)" 1 "打开自动升级"
hb $M_AUTOOFF 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "没开自动的板子:不下发"
hb $M_AUTOOLD 1.3.0 ""
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "开了自动但固件太老:不下发"
eq "$(ota_col $M_AUTOOLD "IFNULL(state,'')")" "" "老固件自动升级静默跳过(不记失败刷屏)"
hb $M_AUTO 1.3.0 ota-remote '"running":"流程A"'
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "自动:跑流程时不下发"
eq "$(ota_col $M_AUTO state)" deferred "自动:状态 deferred"
hb $M_AUTO 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "d['ota']['version']")" 1.4.0 "自动:空闲时下发渠道最新 1.4.0"
eq "$(ota_col $M_AUTO origin)" auto "来源记为 auto"
hb $M_AUTO 1.3.0 ota-remote '"otaReport":{"state":"failed","ver":"1.4.0","msg":"下载超时"}'
eq "$(ota_col $M_AUTO last_fail_fw)" 1.4.0 "自动失败:记下失败的版本"
hb $M_AUTO 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "失败过的 1.4.0 不再自动重试"
FW_C=$(upload_fw 1.4.1 stable)
hb $M_AUTO 1.3.0 ota-remote
eq "$(jq_ $TMP/hb.json "d['ota']['version']")" 1.4.1 "发布了 1.4.1:自动下发新版本"
hb $M_AUTO 1.4.1 ota-remote
eq "$(ota_col $M_AUTO state)" success "自动升级到 1.4.1 成功"
hb $M_AUTO 1.4.1 ota-remote
eq "$(jq_ $TMP/hb.json "'ota' in d")" False "已是最新:不再下发"
adm POST /api/admin/ota/auto "{\"macs\":[\"$M_AUTO\"],\"auto\":false}"
eq "$(ota_col $M_AUTO auto)" 0 "关掉自动升级"

# ---------------------------------------------------------------- 8. 能力不被代报清掉 + 后台接口
log "8. App 代报不清能力;后台列表 / 详情带升级状态"
curl -s -o /dev/null -X POST "$URL/api/v1/app/board-relay" -H "X-Token: $AT" -H 'Content-Type: application/json' \
  -d "{\"boardMac\":\"$M_OK\",\"deviceId\":\"p1\",\"status\":{\"fw\":\"1.4.0\",\"heap\":1}}"
eq "$(tmysql $DB -e "SELECT caps FROM boards WHERE mac='$M_OK'")" ota-remote "App 代报后能力还在"
curl -s -b "$TMP/ck" "$URL/api/admin/boards" -o "$TMP/boards.json"
eq "$(jq_ $TMP/boards.json "[b for b in d['boards'] if b['mac']=='$M_OK'][0]['remoteOta']")" True "列表:支持远程"
eq "$(jq_ $TMP/boards.json "[b for b in d['boards'] if b['mac']=='$M_OLD'][0]['remoteOta']")" False "列表:老固件不支持"
eq "$(jq_ $TMP/boards.json "[b for b in d['boards'] if b['mac']=='$M_OK'][0]['ota']['stateText']")" 已升级 "列表:升级状态文字"
eq "$(jq_ $TMP/boards.json "[b for b in d['boards'] if b['mac']=='$M_AUTOOFF'][0]['ota']['state']")" "" "列表:没升级过的板子状态为空"
curl -s -b "$TMP/ck" "$URL/api/admin/board/$M_FAIL" -o "$TMP/one.json"
eq "$(jq_ $TMP/one.json "d['ota']['toFw']")" 1.4.0 "详情:带升级状态"

# ---------------------------------------------------------------- 9. USB 刷机文件
log "9. USB 刷机文件(构建产物 → 发布 → 删除 / 清理)"
curl -s -b "$TMP/ck" -o "$TMP/f.json" -w '%{http_code}' "$URL/api/admin/flash/fw-$FW_B" > "$TMP/code"
eq "$(cat $TMP/code)" 409 "手工上传的固件没有整片文件:409"
has "$(jq_ $TMP/f.json "d['message']")" "一键构建" "提示用一键构建的版本"
eq "$(curl -s -o /dev/null -w '%{http_code}' "$URL/api/admin/flash/fw-$FW_B")" 401 "没登录:401"

# 伪造一次构建成功的固件(目录结构和 tools/tp-build.sh 第 5 步产出的一样)
ADMIN_ID=$(tmysql $DB -e "SELECT id FROM users WHERE username='admin'")
tmysql $DB -e "INSERT INTO builds (kind, branch, version_name, channel, status, artifact, size, sha256, created_by, finished_at)
  VALUES ('firmware','test','1.5.0','beta','success','fw-1.5.0-ble.bin',0,'',$ADMIN_ID,NOW())"
BID=$(tmysql $DB -e "SELECT MAX(id) FROM builds")
BD="$SRV/data/builds/$BID"
mkdir -p "$BD/parts"
head -c 300000 /dev/urandom > "$BD/fw-1.5.0-ble.bin"
head -c 20000 /dev/urandom > "$BD/parts/bootloader.bin"
head -c 3072 /dev/urandom > "$BD/parts/partitions.bin"
head -c 8192 /dev/urandom > "$BD/parts/boot_app0.bin"
cat > "$BD/parts/flash.json" <<'JSON'
{
  "chip": "esp32s3", "flashMode": "dio", "flashFreq": "80m", "flashSize": "8MB",
  "files": [
    { "name": "bootloader", "file": "bootloader.bin", "address": 0 },
    { "name": "partitions", "file": "partitions.bin", "address": 32768 },
    { "name": "boot_app0",  "file": "boot_app0.bin",  "address": 57344 },
    { "name": "app",        "file": "app",            "address": 65536 }
  ]
}
JSON
APP_SHA=$(sha256sum "$BD/fw-1.5.0-ble.bin" | cut -d' ' -f1)
tmysql $DB -e "UPDATE builds SET size=300000, sha256='$APP_SHA' WHERE id=$BID"
curl -s -b "$TMP/ck" "$URL/api/admin/builds" -o "$TMP/b.json"
eq "$(jq_ $TMP/b.json "[b for b in d['list'] if b['id']==$BID][0]['usbFlash']")" True "构建记录:标为可 USB 刷"
curl -s -b "$TMP/ck" "$URL/api/admin/flash/build-$BID" -o "$TMP/f.json"
eq "$(jq_ $TMP/f.json "','.join('%s@%d'%(f['name'],f['address']) for f in d['files'])")" \
  "bootloader@0,partitions@32768,boot_app0@57344,app@65536" "刷机清单:4 段 + 地址"
eq "$(jq_ $TMP/f.json "d['flashSize']+'/'+d['flashMode']+'/'+d['flashFreq']")" "8MB/dio/80m" "刷机参数"
# 下载每一段,和源文件比 sha256:$1 src  $2 段名  $3 源文件
seg_ok() {
  local a b
  a=$(curl -s -b "$TMP/ck" "$URL/api/admin/flash/$1/$2" | sha256sum | cut -d' ' -f1)
  b=$(sha256sum "$3" | cut -d' ' -f1)
  eq "$a" "$b" "$1 的 $2 段下载内容正确"
}
seg_ok build-$BID bootloader "$BD/parts/bootloader.bin"
seg_ok build-$BID partitions "$BD/parts/partitions.bin"
seg_ok build-$BID boot_app0 "$BD/parts/boot_app0.bin"
seg_ok build-$BID app "$BD/fw-1.5.0-ble.bin"
eq "$(curl -s -b "$TMP/ck" -o /dev/null -w '%{http_code}' "$URL/api/admin/flash/build-$BID/nvs")" 404 "清单外的段名:404"
eq "$(curl -s -b "$TMP/ck" -o /dev/null -w '%{http_code}' "$URL/api/admin/flash/..%2F..%2Fetc")" 404 "乱写的来源:404"

adm POST /api/admin/builds/$BID/publish '{}'
eq "$(jq_ $TMP/adm.json "d['ok']")" True "发布这个构建"
FW_D=$(tmysql $DB -e "SELECT published_id FROM builds WHERE id=$BID")
FILE_D=$(tmysql $DB -e "SELECT file FROM firmware WHERE id=$FW_D")
[ -f "$SRV/data/firmware/$FILE_D.parts/flash.json" ] && pass "发布后刷机文件跟着固件存了一份" || fail "发布后没有 $FILE_D.parts"
curl -s -b "$TMP/ck" "$URL/api/admin/firmware" -o "$TMP/fl.json"
eq "$(jq_ $TMP/fl.json "[f for f in d['list'] if f['id']==$FW_D][0]['usbFlash']")" True "固件列表:已发布的标为可 USB 刷"
eq "$(jq_ $TMP/fl.json "[f for f in d['list'] if f['id']==$FW_B][0]['usbFlash']")" False "固件列表:手工上传的不可 USB 刷"
seg_ok fw-$FW_D app "$BD/fw-1.5.0-ble.bin"
seg_ok fw-$FW_D bootloader "$BD/parts/bootloader.bin"

# 清理旧版本(每渠道留 5 个)时整片文件一起删:beta 渠道再上传 5 个,1.5.0 被挤掉
for v in 1.5.1 1.5.2 1.5.3 1.5.4 1.5.5; do upload_fw $v beta >/dev/null; done
eq "$(tmysql $DB -e "SELECT COUNT(*) FROM firmware WHERE id=$FW_D")" 0 "beta 渠道超过 5 个:1.5.0 被清理"
[ ! -e "$SRV/data/firmware/$FILE_D.parts" ] && pass "清理时整片文件一起删了" || fail "清理后 $FILE_D.parts 还在"

# 删除固件时整片文件一起删:再伪造 + 发布一次,然后手动删
tmysql $DB -e "INSERT INTO builds (kind, branch, version_name, channel, status, artifact, size, sha256, created_by, finished_at)
  VALUES ('firmware','test','1.6.0','beta','success','fw-1.6.0-ble.bin',300000,'$APP_SHA',$ADMIN_ID,NOW())"
BID2=$(tmysql $DB -e "SELECT MAX(id) FROM builds")
mkdir -p "$SRV/data/builds/$BID2"
cp -r "$BD/parts" "$SRV/data/builds/$BID2/parts"
cp "$BD/fw-1.5.0-ble.bin" "$SRV/data/builds/$BID2/fw-1.6.0-ble.bin"
adm POST /api/admin/builds/$BID2/publish '{}'
FW_E=$(tmysql $DB -e "SELECT published_id FROM builds WHERE id=$BID2")
FILE_E=$(tmysql $DB -e "SELECT file FROM firmware WHERE id=$FW_E")
[ -d "$SRV/data/firmware/$FILE_E.parts" ] || fail "第二次发布没有整片文件"
curl -s -b "$TMP/ck" -o /dev/null -X DELETE "$URL/api/admin/firmware/$FW_E"
[ ! -e "$SRV/data/firmware/$FILE_E.parts" ] && [ ! -e "$SRV/data/firmware/$FILE_E" ] \
  && pass "删除固件:.bin 和整片文件都删了" || fail "删除固件后文件还在"

# 审计
eq "$(tmysql $DB -e "SELECT COUNT(*) > 0 FROM audit_log WHERE action='ota_request'")" 1 "下发升级记了审计"
eq "$(tmysql $DB -e "SELECT COUNT(*) > 0 FROM audit_log WHERE action='ota_auto'")" 1 "自动升级开关记了审计"

grep -E "Error|错误|出错" "$TMP/srv.log" | head -5

echo
[ $FAILS = 0 ] && echo "全部通过 ✅" || echo "失败 $FAILS 项 ❌"
exit $FAILS
