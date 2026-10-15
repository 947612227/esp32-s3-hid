#!/usr/bin/env bash
# ============================================================================
#  后台「一键构建」验证(在服务器上跑,用真实工具链真编译)
#
#  起临时 MySQL(端口 3408)+ 测试服务(端口 3996),用本分支 server/;
#  代码从服务器主仓库 /root/esp32-s3-hid 只读导出,工作目录放在临时目录(不碰正式构建缓存):
#    1. 启动:迁移建 builds 表;/api/admin/build/meta 环境就绪、分支列表、默认优化分支、versionCode 自动取号
#    2. 参数校验:类型 / 版本号非法字符 / 不存在的分支 / 只读账号 → 拒绝
#    3. App 构建:并发第二个被拒;成功后 APK 里的版本号、签名证书正确;日志分段读;主仓库没被改动
#    4. 发布 App:进 APK 列表,手机检查更新拿到它,下载内容和产物一致;重复发布被拒;审计齐全
#    5. 发布前线上已有更大 versionCode → 拒绝发布(提示重新构建)
#    6. 构建失败(工具链坏了):状态 failed、有失败原因、不占 versionCode
#    7. 构建记录保留:每类只留最近 5 次的目录
#    8. 停止构建:状态 cancelled,构建进程全部退出
#    9. 服务重启打断构建:重启后标记失败,残留进程被停掉
#   10. 固件构建 + 发布:产物是 ESP32 镜像且含版本号;beta 板子心跳拿到它;同渠道同版本号不能再建
#
#  用法(仓库根目录): bash tests/server/test_build.sh
#  ⚠️ bash 变量名只能用 ASCII,所以变量是英文 + 中文注释
# ============================================================================
set -u
REPO=$(cd "$(dirname "$0")/../.." && pwd)   # 仓库根目录
PROD_DIR=/root/touchpad-server              # 只借用它的 node_modules(只读)
MAIN_REPO=/root/esp32-s3-hid                # 构建取代码的主仓库(只读)
TMP=/tmp/tp-build-test                      # 临时目录
MY_PORT=3408                                # 临时 MySQL 端口
HTTP_PORT=3996                              # 测试服务端口
T_USER=tp_test
T_PASS=tp_test_pw_$$
DB=tp_build                                 # 测试库名
OPT_BRANCH=opt/stability-20261014           # 默认构建分支
BT=/opt/tp-build/android-sdk/build-tools/35.0.0
CERT=6623f9de5d05a97b16e1d0ce225a5c35f113631025df33115130fe475a27eb3c   # 仓库 debug.keystore 的证书
FAILS=0
SRV_PID=
SRV="$REPO/server"
URL="http://127.0.0.1:$HTTP_PORT"
UNIT_PREFIX=tp-build-p$HTTP_PORT-           # 测试实例的 systemd scope 名前缀

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "  ❌ $*"; FAILS=$((FAILS + 1)); }
pass() { echo "  ✅ $*"; }
info() { echo "  ·  $*"; }

# 安全停临时 MySQL 的公共函数
source "$(cd "$(dirname "$0")" && pwd)/lib_tmp_mysql.sh"

cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null
  # 万一有测试实例的构建 scope 没停,按名字精确停掉
  for u in $(systemctl list-units --no-legend --all "${UNIT_PREFIX}*" 2>/dev/null | awk '{print $1}'); do
    systemctl stop "$u" 2>/dev/null
  done
  # 确认临时 MySQL 真的退出了才删目录(见 lib_tmp_mysql.sh)
  stop_tmp_mysql "$TMP/mysqld.pid" "$TMP/data" && rm -rf "$TMP"
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
rm -rf "$TMP"; mkdir -p "$TMP/work"
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

# 主仓库的「指纹」:HEAD + 所有分支指向 + 构建会改版本号的那几个文件(构建只能读,不能动它)
# ⚠️ 不用 git status —— 它会刷新别的会话正在用的 index
repo_fp() {
  (git --git-dir="$MAIN_REPO/.git" rev-parse HEAD
   git --git-dir="$MAIN_REPO/.git" for-each-ref
   cd "$MAIN_REPO" && md5sum android-app/app/src/main/AndroidManifest.xml platformio.ini secrets.h
  ) 2>&1 | md5sum | cut -c1-12
}
FP0=$(repo_fp)

start_server() {   # $1 = 日志名  其余 = 额外环境变量(KEY=VAL)
  local name=$1; shift
  (cd "$SRV" && exec env PORT=$HTTP_PORT DB_HOST=127.0.0.1 DB_PORT=$MY_PORT DB_USER=$T_USER \
    DB_PASS=$T_PASS DB_NAME=$DB BUILD_WORK="$TMP/work" "$@" node index.js > "$TMP/$name.log" 2>&1) &
  SRV_PID=$!
  for i in $(seq 1 30); do
    curl -s "$URL/api/v1/board/health" | grep -q '"ok":true' && return 0
    sleep 0.5
  done
  echo "  服务没起来:"; tail -20 "$TMP/$name.log"; return 1
}
stop_server() { kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=; }

# 带管理员 cookie 的请求
GET()  { curl -s -b "$TMP/ck" "$URL$1"; }
POST() {   # $1 = 路径  $2 = JSON 请求体(可省)
  local body='{}'
  [ $# -ge 2 ] && body=$2
  curl -s -b "$TMP/ck" -X POST "$URL$1" -H 'Content-Type: application/json' -d "$body"
}
# 取 JSON 字段(python3 系统自带 3.6,够用)
jget() { python3 -c "import json,sys
d=json.load(sys.stdin)
for k in sys.argv[1].split('.'):
    d = d[int(k)] if isinstance(d, list) else d.get(k)
print('' if d is None else (json.dumps(d, ensure_ascii=False) if isinstance(d, (dict, list)) else d))" "$1"; }
bstatus() { GET "/api/admin/builds/$1" | jget build.status; }
# 等构建结束(不是 running),最多 $2 秒
wait_build() {
  local s
  for i in $(seq 1 "$2"); do
    s=$(bstatus "$1"); [ "$s" != running ] && { echo "$s"; return; }
    sleep 1
  done
  echo timeout
}
# 构建进程还有几个:看进程的当前目录是不是在测试工作目录下
# (编译命令行里全是相对路径,pgrep -f 匹配不到)
build_procs() {
  local n=0 c p
  for p in /proc/[0-9]*; do
    c=$(readlink "$p/cwd" 2>/dev/null) || continue
    case "$c" in "$TMP/work"*) n=$((n + 1)) ;; esac
  done
  echo $n
}

# ---------------------------------------------------------------- 1. 启动 / 元信息
log "1. 启动:建表 + 元信息"
start_server s1 || exit 1
ADMIN_PASS=$(sed -n 's/.*密  码: //p' "$TMP/s1.log" | head -1)
curl -s -c "$TMP/ck" -o /dev/null -X POST "$URL/admin/login" -d "username=admin&password=$ADMIN_PASS"
grep -q tp_sess "$TMP/ck" && pass "管理员登录" || { fail "登录失败"; exit 1; }
[ "$(tmysql $DB -e "SHOW TABLES LIKE 'builds'")" = builds ] && pass "builds 表已建" || fail "没有 builds 表"
# 先手工上传一个 versionCode 17,看构建是否接着取 18
head -c 1500 /dev/urandom > "$TMP/a.apk"
curl -s -b "$TMP/ck" -X POST "$URL/api/admin/apk" -F "file=@$TMP/a.apk" -F "versionCode=17" -F "versionName=3.5" >/dev/null
m=$(GET /api/admin/build/meta)
[ "$(echo "$m" | jget env.app.ready)" = True ] && [ "$(echo "$m" | jget env.firmware.ready)" = True ] \
  && pass "构建环境就绪(App + 固件)" || fail "环境:$(echo "$m" | jget env)"
echo "$m" | jget branches | grep -q "\"$OPT_BRANCH\"" && echo "$m" | jget branches | grep -q '"master"' \
  && pass "分支列表含 master 和 $OPT_BRANCH" || fail "分支:$(echo "$m" | jget branches | head -c 300)"
[ "$(echo "$m" | jget defaultBranch)" = "$OPT_BRANCH" ] && pass "默认选优化分支" || fail "默认分支:$(echo "$m" | jget defaultBranch)"
[ "$(echo "$m" | jget nextVersionCode)" = 18 ] && pass "下一个 versionCode = 线上 17 + 1 = 18" \
  || fail "nextVersionCode=$(echo "$m" | jget nextVersionCode)"

# ---------------------------------------------------------------- 2. 参数校验
log "2. 参数校验"
r=$(POST /api/admin/builds '{"kind":"exe","branch":"master","versionName":"1"}')
echo "$r" | grep -q 'kind 只能是' && pass "未知类型 → 拒绝" || fail "未知类型:$r"
r=$(POST /api/admin/builds '{"kind":"app","branch":"master","versionName":"1.0;rm -rf /"}')
echo "$r" | grep -q '版本号只能用' && pass "版本号含非法字符 → 拒绝" || fail "非法版本号:$r"
r=$(POST /api/admin/builds '{"kind":"app","branch":"no-such-branch","versionName":"1.0"}')
echo "$r" | grep -q '没有这个分支' && pass "不存在的分支 → 拒绝" || fail "不存在分支:$r"
POST /api/admin/users '{"username":"viewer1","password":"vpass1","role":"viewer"}' >/dev/null
curl -s -c "$TMP/ck2" -o /dev/null -X POST "$URL/admin/login" -d "username=viewer1&password=vpass1"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$TMP/ck2" -X POST "$URL/api/admin/builds" \
  -H 'Content-Type: application/json' -d '{"kind":"app","branch":"master","versionName":"1.0"}')
[ "$code" = 403 ] && pass "只读账号不能开始构建(403)" || fail "只读账号:HTTP $code"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$TMP/ck2" "$URL/api/admin/builds")
[ "$code" = 200 ] && pass "只读账号能看构建记录" || fail "只读账号看记录:HTTP $code"
[ "$(tmysql $DB -e 'SELECT COUNT(*) FROM builds')" = 0 ] && pass "被拒的请求没留下记录" || fail "被拒请求留了记录"

# ---------------------------------------------------------------- 3. App 构建
log "3. App 构建(真编译)"
r=$(POST /api/admin/builds "{\"kind\":\"app\",\"branch\":\"$OPT_BRANCH\",\"versionName\":\"9.9-test\",\"notes\":\"构建测试\"}")
A1=$(echo "$r" | jget id)
[ "$(echo "$r" | jget versionCode)" = 18 ] && pass "开始构建 #$A1,versionCode 18" || fail "开始构建:$r"
r=$(POST /api/admin/builds "{\"kind\":\"app\",\"branch\":\"$OPT_BRANCH\",\"versionName\":\"9.9\"}")
echo "$r" | grep -q '还没结束' && pass "同时第二个构建 → 拒绝" || fail "并发构建:$r"
s=$(wait_build "$A1" 180)
[ "$s" = success ] && pass "构建成功" || { fail "构建结果 $s"; GET "/api/admin/builds/$A1/log" | jget text | tail -20; }
b=$(GET "/api/admin/builds/$A1")
info "用时 $(( ($(echo "$b" | jget build.finishedAt) - $(echo "$b" | jget build.createdAt)) / 1000 )) 秒,内存峰值 $(( $(echo "$b" | jget build.peakMemKb) / 1024 )) MB"
curl -s -b "$TMP/ck" -o "$TMP/b1.apk" "$URL/api/admin/builds/$A1/artifact"
badging=$("$BT/aapt2" dump badging "$TMP/b1.apk" 2>/dev/null | head -1)
echo "$badging" | grep -q "versionCode='18' versionName='9.9-test'" && pass "APK 里的版本号:18 / 9.9-test" \
  || fail "APK 版本:$badging"
# apksigner 是个 shell 包装,要 PATH 里有 java(系统没装,用构建环境的 JDK 17)
PATH=/opt/tp-build/jdk-17/bin:$PATH "$BT/apksigner" verify --print-certs "$TMP/b1.apk" 2>/dev/null | grep -q "$CERT" \
  && pass "签名证书和仓库 debug.keystore 一致(${CERT:0:12}…)" || fail "签名证书不对"
[ "$(sha256sum "$TMP/b1.apk" | cut -d' ' -f1)" = "$(echo "$b" | jget build.sha256)" ] \
  && pass "下载的产物 sha256 和记录一致" || fail "产物 sha256 对不上"
l1=$(GET "/api/admin/builds/$A1/log?offset=0")
off=$(echo "$l1" | jget offset); size=$(echo "$l1" | jget size)
echo "$l1" | jget text | grep -q '== 结果:成功' && [ "$off" = "$size" ] && pass "日志完整($size 字节,含结果行)" \
  || fail "日志:offset=$off size=$size"
l2=$(GET "/api/admin/builds/$A1/log?offset=100")
[ "$(echo "$l2" | jget offset)" = "$size" ] && [ "$(echo "$l2" | jget done)" = True ] \
  && pass "日志从中间接着读:offset 100 → $size,done=true" || fail "分段读日志:$(echo "$l2" | head -c 200)"
[ "$(repo_fp)" = "$FP0" ] && pass "主仓库 HEAD 和工作区没被改动" || fail "主仓库被改动了!"

# ---------------------------------------------------------------- 4. 发布 App
log "4. 发布 App"
r=$(POST "/api/admin/builds/$A1/publish")
echo "$r" | grep -q '"ok":true' && pass "发布成功:$r" || fail "发布:$r"
[ "$(bstatus "$A1")" = published ] && pass "状态变成 published" || fail "状态:$(bstatus "$A1")"
row=$(tmysql $DB -e "SELECT version_name, sha256, notes FROM apks WHERE version_code=18")
[ "$row" = "$(printf '9.9-test\t%s\t构建测试' "$(echo "$b" | jget build.sha256)")" ] \
  && pass "APK 列表里有 18 / 9.9-test,sha256、说明都对" || fail "apks 行:$row"
AT=$(tmysql $DB -e "SELECT v FROM settings WHERE k='appToken'")
v=$(curl -s "$URL/api/v1/app/version?code=17" -H "X-Token: $AT")
echo "$v" | grep -q '"hasUpdate":true' && echo "$v" | grep -q '"versionCode":18' \
  && pass "手机(17)检查更新 → 拿到 18" || fail "检查更新:$v"
dl=$(echo "$v" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p' | sed "s#http://[^/]*#$URL#")
curl -s -o "$TMP/dl.apk" "$dl" -H "X-Token: $AT"
cmp -s "$TMP/dl.apk" "$TMP/b1.apk" && pass "手机下载到的就是构建产物" || fail "下载内容和产物不一致"
r=$(POST "/api/admin/builds/$A1/publish")
echo "$r" | grep -q '已经发布过' && pass "重复发布 → 拒绝" || fail "重复发布:$r"
[ "$(tmysql $DB -e 'SELECT COUNT(*) FROM apks WHERE version_code=18')" = 1 ] && pass "APK 只有一份" || fail "APK 有多份"
acts=$(tmysql $DB -e "SELECT GROUP_CONCAT(action ORDER BY id) FROM audit_log WHERE action IN ('build_start','build_publish','upload_apk')")
[ "$acts" = "upload_apk,build_start,upload_apk,build_publish" ] && pass "审计:$acts" || fail "审计:$acts"
tmysql $DB -e "SELECT detail FROM audit_log WHERE action='upload_apk' ORDER BY id DESC LIMIT 1" | grep -q "构建 #$A1" \
  && pass "上架审计里注明了来自构建 #$A1" || fail "上架审计没注明构建号"

# ---------------------------------------------------------------- 5. 发布前已被更大的版本超过
log "5. 线上已有更大 versionCode 时拒绝发布"
r=$(POST /api/admin/builds "{\"kind\":\"app\",\"branch\":\"$OPT_BRANCH\",\"versionName\":\"9.10\"}")
A2=$(echo "$r" | jget id)
[ "$(echo "$r" | jget versionCode)" = 19 ] && pass "下一个构建取到 19" || fail "取号:$r"
[ "$(wait_build "$A2" 180)" = success ] && pass "构建 #$A2 成功" || fail "构建 #$A2 失败"
head -c 1500 /dev/urandom > "$TMP/a.apk"
curl -s -b "$TMP/ck" -X POST "$URL/api/admin/apk" -F "file=@$TMP/a.apk" -F "versionCode=25" -F "versionName=手工" >/dev/null
r=$(POST "/api/admin/builds/$A2/publish")
echo "$r" | grep -q '线上已经有 versionCode 25' && pass "线上已有 25 → 拒绝发布 19" || fail "旧号发布:$r"
[ "$(bstatus "$A2")" = success ] && pass "被拒后仍是「成功(未发布)」" || fail "状态:$(bstatus "$A2")"
[ "$(GET /api/admin/build/meta | jget nextVersionCode)" = 26 ] && pass "下一个号变成 26" || fail "下一个号不对"
stop_server

# ---------------------------------------------------------------- 6. 构建失败
log "6. 工具链坏了 → 构建失败"
FAKE=$TMP/fakeroot                          # 假工具链:其它照旧,aapt2 换成必失败的
mkdir -p "$FAKE/android-sdk/build-tools/35.0.0" "$FAKE/android-sdk/platforms"
ln -s /opt/tp-build/jdk-17 "$FAKE/jdk-17"
ln -s /opt/tp-build/android-sdk/platforms/android-35 "$FAKE/android-sdk/platforms/android-35"
for f in /opt/tp-build/android-sdk/build-tools/35.0.0/*; do ln -s "$f" "$FAKE/android-sdk/build-tools/35.0.0/"; done
rm "$FAKE/android-sdk/build-tools/35.0.0/aapt2"
printf '#!/bin/sh\necho "aapt2: 模拟损坏" >&2\nexit 3\n' > "$FAKE/android-sdk/build-tools/35.0.0/aapt2"
chmod +x "$FAKE/android-sdk/build-tools/35.0.0/aapt2"
# 先塞 6 条旧的 App 构建记录 + 目录,看失败构建结束后的清理
for i in 1 2 3 4 5 6; do
  id=$(tmysql $DB -e "INSERT INTO builds (kind,branch,version_name,status,artifact) VALUES ('app','master','old$i','failed','x.apk'); SELECT LAST_INSERT_ID()")
  mkdir -p "$SRV/data/builds/$id"; echo x > "$SRV/data/builds/$id/x.apk"
done
start_server s2 BUILD_ROOT="$FAKE" || exit 1
r=$(POST /api/admin/builds "{\"kind\":\"app\",\"branch\":\"$OPT_BRANCH\",\"versionName\":\"bad\"}")
A3=$(echo "$r" | jget id)
[ "$(wait_build "$A3" 120)" = failed ] && pass "构建 #$A3 失败" || fail "构建 #$A3 状态 $(bstatus "$A3")"
err=$(GET "/api/admin/builds/$A3" | jget build.error)
[ -n "$err" ] && pass "有失败原因:$err" || fail "没有失败原因"
GET "/api/admin/builds/$A3/log" | jget text | grep -q '模拟损坏' && pass "日志里看得到出错的那一行" || fail "日志里没有出错信息"
[ -z "$(GET "/api/admin/builds/$A3" | jget build.artifact)" ] && pass "失败的构建没有产物" || fail "失败构建有产物"
[ "$(GET /api/admin/build/meta | jget nextVersionCode)" = 26 ] && pass "失败的构建不占 versionCode(下一个仍是 26)" \
  || fail "失败构建占了号"
r=$(POST "/api/admin/builds/$A3/publish")
echo "$r" | grep -q '只有构建成功的才能发布' && pass "失败的构建不能发布" || fail "发布失败构建:$r"

# ---------------------------------------------------------------- 7. 构建记录保留
log "7. 每类只留最近 5 次构建的目录"
dirs=$(tmysql $DB -e "SELECT id FROM builds WHERE kind='app' ORDER BY id DESC" | while read -r id; do
  [ -d "$SRV/data/builds/$id" ] && echo "$id"; done | wc -l)
total=$(tmysql $DB -e "SELECT COUNT(*) FROM builds WHERE kind='app'")
[ "$dirs" = 5 ] && pass "App 共 $total 条记录,只剩最近 5 个目录" || fail "App 目录剩 $dirs 个"
newest5=$(tmysql $DB -e "SELECT GROUP_CONCAT(id ORDER BY id DESC) FROM (SELECT id FROM builds WHERE kind='app' ORDER BY id DESC LIMIT 5) t")
have=$(ls "$SRV/data/builds" | sort -rn | tr '\n' ',' | sed 's/,$//')
[ "$have" = "$newest5" ] && pass "留下的正好是最新的 5 个:$have" || fail "留下 $have,应是 $newest5"
old=$(tmysql $DB -e "SELECT COUNT(*) FROM builds WHERE kind='app' AND artifact='' AND status='failed' AND version_name LIKE 'old%'")
[ "$old" -ge 2 ] && pass "被清理的记录产物字段已清空($old 条),记录本身保留" || fail "被清理记录 artifact 没清空"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$TMP/ck" "$URL/api/admin/builds/$(tmysql $DB -e "SELECT MIN(id) FROM builds")/artifact")
[ "$code" = 410 ] && pass "被清理的产物下载 → 410" || fail "被清理产物下载:HTTP $code"
stop_server

# ---------------------------------------------------------------- 8. 停止构建
log "8. 停止正在跑的固件构建"
start_server s3 || exit 1
r=$(POST /api/admin/builds "{\"kind\":\"firmware\",\"branch\":\"$OPT_BRANCH\",\"versionName\":\"1.9.8-test\",\"channel\":\"beta\"}")
F1=$(echo "$r" | jget id)
for i in $(seq 1 60); do GET "/api/admin/builds/$F1/log" | jget text | grep -q '编译固件' && break; sleep 1; done
sleep 3
info "停止前构建进程 $(build_procs) 个"
r=$(POST "/api/admin/builds/$F1/cancel")
echo "$r" | grep -q '"ok":true' && pass "停止请求成功" || fail "停止:$r"
[ "$(wait_build "$F1" 30)" = cancelled ] && pass "状态 cancelled" || fail "状态 $(bstatus "$F1")"
sleep 2
[ "$(build_procs)" = 0 ] && pass "构建进程全部退出" || fail "还有 $(build_procs) 个构建进程"
systemctl is-active -q "${UNIT_PREFIX}$F1.scope" && fail "scope 还在" || pass "systemd scope 已停"
r=$(POST /api/admin/builds "{\"kind\":\"firmware\",\"branch\":\"$OPT_BRANCH\",\"versionName\":\"1.9.8-test\",\"channel\":\"beta\"}")
F2=$(echo "$r" | jget id)
[ -n "$F2" ] && pass "停止后可以马上开始下一个(#$F2)" || fail "停止后不能开始:$r"

# ---------------------------------------------------------------- 9. 服务重启打断构建
log "9. 构建中重启服务"
for i in $(seq 1 60); do GET "/api/admin/builds/$F2/log" | jget text | grep -q '编译固件' && break; sleep 1; done
sleep 3
info "重启前构建进程 $(build_procs) 个"
stop_server
start_server s4 || exit 1
sleep 2
[ "$(bstatus "$F2")" = failed ] && pass "重启后标记为失败" || fail "状态 $(bstatus "$F2")"
GET "/api/admin/builds/$F2" | jget build.error | grep -q '服务重启' && pass "失败原因:服务重启,构建被中断" || fail "失败原因不对"
[ "$(build_procs)" = 0 ] && pass "残留的构建进程被停掉" || fail "还有 $(build_procs) 个构建进程"
grep -q '中断的构建标记为失败' "$TMP/s4.log" && pass "启动日志里有记录" || fail "启动日志没记录"

# ---------------------------------------------------------------- 10. 固件构建 + 发布
log "10. 固件构建 + 发布(真编译,首次全量要几分钟)"
r=$(POST /api/admin/builds "{\"kind\":\"firmware\",\"branch\":\"$OPT_BRANCH\",\"versionName\":\"1.9.9-test\",\"channel\":\"beta\",\"notes\":\"固件构建测试\"}")
F3=$(echo "$r" | jget id)
s=$(wait_build "$F3" 900)
[ "$s" = success ] && pass "固件构建 #$F3 成功" || { fail "固件构建 $s"; GET "/api/admin/builds/$F3/log" | jget text | tail -30; }
b=$(GET "/api/admin/builds/$F3")
info "用时 $(( ($(echo "$b" | jget build.finishedAt) - $(echo "$b" | jget build.createdAt)) / 1000 )) 秒,内存峰值 $(( $(echo "$b" | jget build.peakMemKb) / 1024 )) MB,$(echo "$b" | jget build.size) 字节"
curl -s -b "$TMP/ck" -o "$TMP/f.bin" "$URL/api/admin/builds/$F3/artifact"
[ "$(od -An -tx1 -N1 "$TMP/f.bin" | tr -d ' ')" = e9 ] && pass "产物是 ESP32 镜像(头字节 0xE9)" || fail "产物头不对"
grep -aq '1.9.9-test' "$TMP/f.bin" && pass "固件里编进了版本号 1.9.9-test" || fail "固件里没有版本号"
GET "/api/admin/builds/$F3/log" | jget text | grep -q 'NimBLE-Arduino @ 2.5.1' && pass "NimBLE 用的是 2.5.1" || fail "NimBLE 版本不对"
r=$(POST "/api/admin/builds/$F3/publish")
echo "$r" | grep -q '"ok":true' && pass "发布成功" || fail "发布:$r"
row=$(tmysql $DB -e "SELECT channel, notes FROM firmware WHERE version='1.9.9-test'")
[ "$row" = "$(printf 'beta\t固件构建测试')" ] && pass "固件列表里有 beta 1.9.9-test" || fail "firmware 行:$row"
BTK=$(tmysql $DB -e "SELECT v FROM settings WHERE k='boardToken'")
hb=$(curl -s -X POST "$URL/api/v1/board/heartbeat" -H "X-Token: $BTK" -H 'Content-Type: application/json' \
  -d '{"mac":"aa:bb:cc:dd:ee:09","fw":"1.3.0","channel":"beta","flows":0}')
echo "$hb" | grep -q '"version":"1.9.9-test"' && pass "beta 板子心跳拿到 1.9.9-test" || fail "beta 心跳:$hb"
hb=$(curl -s -X POST "$URL/api/v1/board/heartbeat" -H "X-Token: $BTK" -H 'Content-Type: application/json' \
  -d '{"mac":"aa:bb:cc:dd:ee:0a","fw":"1.3.0","flows":0}')
echo "$hb" | grep -q '"hasUpdate":false' && pass "stable 板子不受影响" || fail "stable 心跳:$hb"
fid=$(tmysql $DB -e "SELECT id FROM firmware WHERE version='1.9.9-test'")
curl -s -o "$TMP/f_dl.bin" "$URL/api/v1/board/firmware/download/$fid" -H "X-Token: $BTK"
cmp -s "$TMP/f_dl.bin" "$TMP/f.bin" && pass "板子下载到的就是构建产物" || fail "板子下载内容不一致"
r=$(POST /api/admin/builds "{\"kind\":\"firmware\",\"branch\":\"$OPT_BRANCH\",\"versionName\":\"1.9.9-test\",\"channel\":\"beta\"}")
echo "$r" | grep -q '已经有 1.9.9-test' && pass "beta 已有同版本号 → 不能再建" || fail "同版本号:$r"
[ "$(repo_fp)" = "$FP0" ] && pass "全程主仓库没被改动" || fail "主仓库被改动了!"
stop_server

if grep -qE "TypeError|ReferenceError|\[构建\] #[0-9]+ 异常|ER_" "$TMP"/s*.log; then
  fail "服务日志有异常:"; grep -nE "TypeError|ReferenceError|\[构建\] #[0-9]+ 异常|ER_" "$TMP"/s*.log | head -5
else pass "服务日志无异常"; fi

echo
[ $FAILS -eq 0 ] && echo "全部通过" || echo "失败 $FAILS 项"
exit $FAILS
