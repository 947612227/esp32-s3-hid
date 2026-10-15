# ============================================================================
#  服务端测试共用:安全停掉临时 MySQL(被各 test_*.sh 用 source 引入)
#
#  ⚠️ 以前各脚本是 `kill $(cat mysqld.pid); sleep 2; rm -rf $TMP`:
#     mysqld 2 秒内没退完,目录就被删了,进程成了孤儿,继续往已删除的文件里写,
#     磁盘空间看不见也释放不掉。2026-10-15 实测把 40G 根分区写满,
#     正式库的提交卡在 "waiting for handler commit" 近两小时。
#     所以这里:先核对是不是测试实例 → 等它真的退出(最多 60 秒)→ 还在就 kill -9 → 确认没了才返回 0。
# ============================================================================

# 停掉临时 MySQL
#   $1 pid 文件路径   $2 这个实例的 datadir(用来核对进程身份,别误杀正式库)
# 返回 0 = 已停(或本来就没在跑);1 = 停不掉(调用方不要删目录)
stop_tmp_mysql() {
  local pid_file=$1 data_dir=$2 pid i
  pid=$(cat "$pid_file" 2>/dev/null)
  [ -n "$pid" ] && [ -d "/proc/$pid" ] || return 0
  if ! tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q -- "--datadir=$data_dir "; then
    echo "  ⚠️ pid $pid 不是测试实例($data_dir),不动它"
    return 0
  fi
  kill "$pid" 2>/dev/null
  for i in $(seq 1 60); do [ -d "/proc/$pid" ] || return 0; sleep 1; done
  echo "  ⚠️ 临时 MySQL($pid)60 秒没退出,强制结束"
  kill -9 "$pid" 2>/dev/null
  for i in $(seq 1 10); do [ -d "/proc/$pid" ] || return 0; sleep 1; done
  echo "  ❌ 临时 MySQL($pid)停不掉,保留 $data_dir,请手工处理"
  return 1
}
