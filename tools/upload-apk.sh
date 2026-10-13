#!/usr/bin/env bash
# ============================================================================
#  把刚构建好的 APK 上传到控制中心(供 App 检查更新下载)
#
#  为什么要有这个脚本
#  ────────────────────────────────────────────────────────────────────────────
#  约定:APK 改完 → 装到手机 → 手机上验证没问题 → **立刻上传服务器**。
#  以前这一步要手工登录后台、点上传、填版本号,容易忘 ——
#  结果"App 检查更新"永远告诉用户已是最新,而实际本机已经有新版本了。
#
#  用法
#  ────────────────────────────────────────────────────────────────────────────
#    ./tools/upload-apk.sh                  # 上传(版本号从 AndroidManifest 读)
#    ./tools/upload-apk.sh -n "改了登录逻辑"  # 带更新说明(会显示在 App 的更新弹窗)
#    ./tools/upload-apk.sh -c                # 只检查当前服务器上的版本,不上传
#
#  凭证:默认用 admin / 7f373c21c1cf(测试环境)。
#        要覆盖就设环境变量 TP_ADMIN_USER / TP_ADMIN_PASS。
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER="${TP_SERVER:-http://120.55.183.206}"
USER="${TP_ADMIN_USER:-admin}"
PASS="${TP_ADMIN_PASS:-7f373c21c1cf}"
APK="android-app/build/触摸注入板.apk"
MANIFEST="android-app/app/src/main/AndroidManifest.xml"

NOTES=""
CHECK_ONLY=0
while getopts "n:ch" opt; do
  case $opt in
    n) NOTES="$OPTARG" ;;
    c) CHECK_ONLY=1 ;;
    h) sed -n '2,20p' "$0"; exit 0 ;;
    *) exit 1 ;;
  esac
done

# ---- 1. 从 AndroidManifest 读版本号(唯一真相来源)----
if [ ! -f "$MANIFEST" ]; then echo "❌ 找不到 $MANIFEST"; exit 1; fi
CODE=$(grep -oE 'android:versionCode="[0-9]+"' "$MANIFEST" | grep -oE '[0-9]+')
NAME=$(grep -oE 'android:versionName="[^"]+"' "$MANIFEST" | sed 's/.*="//;s/"//')
if [ -z "$CODE" ] || [ -z "$NAME" ]; then echo "❌ 读不到版本号"; exit 1; fi
echo "  本地版本: versionCode=$CODE  versionName=$NAME"

# ---- 2. 登录拿会话 ----
COOKIE=$(mktemp)
trap 'rm -f "$COOKIE"' EXIT
curl -s -c "$COOKIE" -X POST "$SERVER/admin/login" \
  -d "username=$USER&password=$PASS" -o /dev/null || { echo "❌ 登录请求失败"; exit 1; }
if ! grep -q tp_sess "$COOKIE"; then echo "❌ 登录失败(账号密码错?服务器不在?)"; exit 1; fi

# ---- 3. 看服务器上现在是什么版本 ----
CUR=$(curl -s -b "$COOKIE" "$SERVER/api/admin/apk" | python3 -c "
import sys, json
try:
    l = json.load(sys.stdin).get('list', [])
    print(f\"{l[0]['version_code']}|{l[0]['version_name']}|{l[0]['created_at'][:19]}\" if l else '0|(无)|')
except Exception:
    print('?|?|')")
echo "  服务器上: $CUR"

CUR_CODE=$(echo "$CUR" | cut -d'|' -f1)
if [ "$CHECK_ONLY" = "1" ]; then
  if [ "$CUR_CODE" = "$CODE" ]; then
    echo "  ✅ 版本一致(都是 $CODE),不用重传"
  else
    echo "  ⚠️ 不一致:本机 $CODE,服务器 $CUR_CODE —— 需要 ./tools/upload-apk.sh"
  fi
  exit 0
fi

# ---- 4. 上传 ----
if [ ! -f "$APK" ]; then echo "❌ 找不到 $APK,先跑 android-app/build.sh"; exit 1; fi
SIZE=$(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK")
echo "  上传中: $APK ($((SIZE/1024)) KB) …"

RESP=$(curl -s -b "$COOKIE" -X POST "$SERVER/api/admin/apk" \
  -F "file=@$APK;type=application/vnd.android.package-archive" \
  -F "versionCode=$CODE" -F "versionName=$NAME" -F "notes=$NOTES")

if echo "$RESP" | grep -q '"ok":true'; then
  echo "  ✅ 已上传,App 里「检查更新」现在能看到 $NAME ($CODE)"
  if [ "$CUR_CODE" != "0" ] && [ "$CUR_CODE" != "?" ] && [ "$CODE" -le "$CUR_CODE" ] 2>/dev/null; then
    echo "  ⚠️ 注意:versionCode($CODE) 不大于服务器上的($CUR_CODE),"
    echo "     App 会认为「已是最新」。真的要发新版本请先升 AndroidManifest 里的 versionCode。"
  fi
else
  echo "  ❌ 上传失败: $RESP"
  exit 1
fi
