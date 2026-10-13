#!/usr/bin/env bash
# 把 server/ 部署到服务器并重启服务
#   SSHPASS=密码 HOST=120.55.183.206 tools/deploy.sh
set -euo pipefail
HOST="${HOST:-120.55.183.206}"
DEST=/root/touchpad-server
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SSH="sshpass -e ssh -o StrictHostKeyChecking=no"

echo "== 1/4 同步文件 =="
$SSH "root@$HOST" "mkdir -p $DEST"
sshpass -e rsync -az --delete \
  --exclude 'data/' --exclude 'node_modules/' --exclude '.git' \
  -e "ssh -o StrictHostKeyChecking=no" \
  "$HERE/" "root@$HOST:$DEST/"
echo "   ✓ 已同步"

echo "== 2/4 装依赖(Node 16,纯 JS 包) =="
$SSH "root@$HOST" "cd $DEST && npm install --production --no-audit --no-fund 2>&1 | tail -2"

echo "== 3/4 装 systemd 服务 =="
$SSH "root@$HOST" "cp $DEST/deploy/touchpad-server.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable touchpad-server >/dev/null 2>&1"

echo "== 4/4 重启 =="
$SSH "root@$HOST" "systemctl restart touchpad-server && sleep 2 && systemctl is-active touchpad-server"
echo "   ✓ 完成"
