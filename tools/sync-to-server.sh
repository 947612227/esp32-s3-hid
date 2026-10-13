#!/usr/bin/env bash
# ============================================================================
#  把本机代码同步到服务器并推送到 GitHub
#
#  为什么这么绕:
#    ① GitHub 的 HTTPS 主站在国内**被干扰**(实测 curl 3 次全超时),
#       但 **SSH 443 是通的** ⇒ 只能走 SSH
#    ② 服务器上没有 ESP32 工具链、也不该有 ⇒ 服务器只做**版本库 + 分发**
#    ③ 所以流程是:本机改代码 → 同步到服务器 → 服务器提交并推送
#
#  用法:./tools/sync-to-server.sh "这次改了什么"
#
#  ══════════════════════════════════════════════════════════════════════════
#  ⚠️⚠️ **铁律:本机永远不直接推 GitHub**
#
#      这台开发机**禁止**推 GitHub(只允许推公司的 GitLab)。
#      所以本脚本**只做"上传到服务器"**,推送这一步**在服务器上执行** ✅
#      —— 服务器上没有这条限制。
#
#      本脚本运行前会检查:如果本机存在任何指向 github.com 的 remote,
#      直接**报错退出**,避免误操作。
#  ══════════════════════════════════════════════════════════════════════════
# ============================================================================
set -euo pipefail

MSG="${1:-同步更新 $(date '+%Y-%m-%d %H:%M')}"
HOST="root@120.55.183.206"
REMOTE_DIR="/root/esp32-s3-hid"
: "${SSHPASS:?请先 export SSHPASS=<服务器密码>}"

cd "$(dirname "$0")/.."

# ---- 铁律检查:本机不许有 github remote ----
if git remote -v 2>/dev/null | grep -q "github.com"; then
  echo "❌ 本机存在指向 github.com 的 remote:"
  git remote -v | grep github.com | sed 's/^/     /'
  echo "   本机**禁止**推 GitHub(只允许公司 GitLab)。"
  echo "   推送请在服务器上做,或先 git remote remove <name>。"
  exit 1
fi
echo "✅ 铁律检查:本机无 github remote(推送只在服务器上做)"

echo "== 1/4 打包 =="
# ⚠️ COPYFILE_DISABLE=1 关掉 macOS 的 xattr,否则 tar 会刷几百行
#    "Ignoring unknown extended header keyword LIBARCHIVE.xattr..."
COPYFILE_DISABLE=1 tar \
  --exclude='.pio' --exclude='.git' --exclude='node_modules' \
  --exclude='*.o' --exclude='*.d' --exclude='__pycache__' \
  --exclude='._*' --exclude='.DS_Store' \
  -czf /tmp/sync.tgz .
echo "   $(du -h /tmp/sync.tgz | cut -f1)"

echo "== 2/4 上传 =="
sshpass -e scp -q -o StrictHostKeyChecking=no /tmp/sync.tgz "$HOST:/root/"

echo "== 3/4 服务器上更新并提交 =="
sshpass -e ssh -q -o StrictHostKeyChecking=no "$HOST" "bash -s" <<EOF
set -e
cd $REMOTE_DIR
# 保留 .git(不要动版本库),只覆盖工作区文件
tar -xzf /root/sync.tgz --exclude='./.git' 2>/dev/null
rm -f /root/sync.tgz
find . -name '._*' -delete 2>/dev/null || true
git add -A
if git diff --cached --quiet; then
  echo "   没有变化,跳过提交"
else
  git -c user.name="张佳" -c user.email="zhangjia@aviagames.com" \
      commit -q -m "$MSG"
  echo "   已提交: \$(git log --oneline -1)"
fi
EOF

echo "== 4/4 推送(**在服务器上执行**,不是本机)=="
sshpass -e ssh -q -o StrictHostKeyChecking=no "$HOST" \
  "cd $REMOTE_DIR && git push origin \$(git rev-parse --abbrev-ref HEAD) 2>&1 | tail -4"

rm -f /tmp/sync.tgz
echo "✅ 完成"
