#!/usr/bin/env bash
# ============================================================================
#  在服务器上装「后台一键构建」要用的工具链(可重复执行,装过的会跳过)
#
#  装到 /opt/tp-build/:
#    jdk-17/                  JDK 17(App 用 javac / d8 / apksigner)
#    android-sdk/             build-tools 35.0.0 + platforms/android-35(和 Mac 上同一版本)
#    python-3.11/             独立 Python 3.11(系统自带的 3.6 太老,见第 4 步)
#    pio-venv/                PlatformIO Core(Python 3.11 虚拟环境)
#    platformio/              PlatformIO 的平台、工具链、框架(PLATFORMIO_CORE_DIR)
#    work/                    构建工作目录(固件的 .pio 编译缓存留在这里,第二次起只编改动的文件)
#    secrets.h                固件凭据(不进 git;权限 600,不在任何对外目录下)
#  另外:没有 swap 时建 2G 的 /swapfile,vm.swappiness=10(只在内存真不够时才换出)
#
#  ⚠️ 服务器访问不了 dl.google.com 和 Docker Hub,所以全部走国内镜像:
#     JDK → 清华 Adoptium;Android SDK → 腾讯云 AndroidSDK;pip → 阿里云
#  ⚠️ 版本和 Mac 上实际在用的对齐(2026-10-15 核对):
#     espressif32 7.1.3、arduino 框架 4.20017.260907、NimBLE-Arduino 2.5.1、PlatformIO 6.1.16
#
#  用法(服务器上,root):bash server/tools/setup-build-env.sh
# ============================================================================
set -euo pipefail

ROOT=/opt/tp-build                                   # 工具链根目录
DL=$ROOT/dl                                          # 下载缓存
JDK_URL=https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz
BT_URL=https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip
PF_URL=https://mirrors.cloud.tencent.com/AndroidSDK/platform-35_r02.zip
PY_URL="https://github.com/astral-sh/python-build-standalone/releases/download/20261013/cpython-3.11.17%2B20261013-x86_64-unknown-linux-gnu-install_only.tar.gz"
PY_SHA256=846bf682e2b67393f2affa5e5c0994e446abc3a7cc5bb699890c36346823644c   # GitHub API 给的 digest
PIP_INDEX=https://mirrors.aliyun.com/pypi/simple/
PIO_VER=6.1.16                                       # PlatformIO Core 版本
PLATFORM_SPEC="platformio/espressif32@7.1.3"         # 板子平台版本
FRAMEWORK_SPEC="platformio/framework-arduinoespressif32@4.20017.260907"

log() { echo "[$(date +%H:%M:%S)] $*"; }
mkdir -p "$ROOT" "$DL" "$ROOT/work"

# 解 zip:服务器没有 unzip,用 Python 解,并保留可执行权限
unzip_py() {   # $1 = zip  $2 = 目标目录
  python3 - "$1" "$2" <<'PY'
import os, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for i in z.infolist():
    p = z.extract(i, sys.argv[2])
    mode = (i.external_attr >> 16) & 0o777
    if mode: os.chmod(p, mode)
PY
}

# 下载(已下载过就跳过)
fetch() {      # $1 = URL  → 打印本地路径
  local f="$DL/$(basename "$1")"
  if [ ! -s "$f" ]; then
    curl -fsSL --retry 3 -m 900 -o "$f.part" "$1"
    mv "$f.part" "$f"
  fi
  echo "$f"
}

# ---------------------------------------------------------------- 1. swap
log "1. swap"
if [ "$(swapon --noheadings | wc -l)" -eq 0 ]; then
  if [ ! -f /swapfile ]; then
    dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
  fi
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile swap swap defaults 0 0' >> /etc/fstab
  echo 'vm.swappiness = 10' > /etc/sysctl.d/99-tp-build.conf
  sysctl -q -p /etc/sysctl.d/99-tp-build.conf
  log "   已建 2G /swapfile,swappiness=10"
else
  log "   已有 swap,跳过"
fi

# ---------------------------------------------------------------- 2. JDK 17
log "2. JDK 17"
if [ ! -x "$ROOT/jdk-17/bin/javac" ]; then
  f=$(fetch "$JDK_URL")
  rm -rf "$ROOT/jdk-17.tmp"; mkdir -p "$ROOT/jdk-17.tmp"
  tar xzf "$f" -C "$ROOT/jdk-17.tmp" --strip-components=1
  rm -rf "$ROOT/jdk-17"; mv "$ROOT/jdk-17.tmp" "$ROOT/jdk-17"
fi
log "   $("$ROOT/jdk-17/bin/javac" -version 2>&1)"

# ---------------------------------------------------------------- 3. Android SDK
log "3. Android build-tools 35.0.0 + platform android-35"
SDK=$ROOT/android-sdk
if [ ! -x "$SDK/build-tools/35.0.0/aapt2" ]; then
  f=$(fetch "$BT_URL")
  rm -rf "$DL/bt"; unzip_py "$f" "$DL/bt"
  mkdir -p "$SDK/build-tools"; rm -rf "$SDK/build-tools/35.0.0"
  mv "$DL/bt/$(ls "$DL/bt" | head -1)" "$SDK/build-tools/35.0.0"; rm -rf "$DL/bt"
fi
if [ ! -f "$SDK/platforms/android-35/android.jar" ]; then
  f=$(fetch "$PF_URL")
  rm -rf "$DL/pf"; unzip_py "$f" "$DL/pf"
  mkdir -p "$SDK/platforms"; rm -rf "$SDK/platforms/android-35"
  mv "$DL/pf/$(ls "$DL/pf" | head -1)" "$SDK/platforms/android-35"; rm -rf "$DL/pf"
fi
log "   $("$SDK/build-tools/35.0.0/aapt2" version 2>&1)"
log "   android.jar sha256 $(sha256sum "$SDK/platforms/android-35/android.jar" | cut -c1-16)"

# ---------------------------------------------------------------- 4. Python 3.11
# ⚠️ 系统自带 Python 3.6 不够用:tool-esptoolpy 装完会用 pip 装 cryptography~=46,
#    3.6 装不上 → 它的安装脚本会删掉自己的 package.json,固件编译时报
#    MissingPackageManifestError(实测踩过)。yum 里也没有更新的 Python,
#    所以用 python-build-standalone 的预编译包(支持 glibc 2.17)。
#    GitHub 下载在服务器上很慢/会卡住,卡住就在别的机器下好,放到 $DL/ 下再跑本脚本。
log "4. Python 3.11"
if [ ! -x "$ROOT/python-3.11/bin/python3" ]; then
  f=$(fetch "$PY_URL")
  echo "$PY_SHA256  $f" | sha256sum -c --quiet || { echo "Python 包校验失败: $f" >&2; exit 1; }
  rm -rf "$ROOT/python-3.11.tmp"; mkdir -p "$ROOT/python-3.11.tmp"
  tar xzf "$f" -C "$ROOT/python-3.11.tmp" --strip-components=1
  rm -rf "$ROOT/python-3.11"; mv "$ROOT/python-3.11.tmp" "$ROOT/python-3.11"
fi
log "   $("$ROOT/python-3.11/bin/python3" --version)"

# ---------------------------------------------------------------- 5. PlatformIO
log "5. PlatformIO $PIO_VER"
# 旧版脚本用 Python 3.6 建过 venv,发现不是 3.11 就重建
if [ -x "$ROOT/pio-venv/bin/python" ] \
   && ! "$ROOT/pio-venv/bin/python" -c 'import sys; sys.exit(sys.version_info[:2] != (3, 11))'; then
  log "   旧 venv 不是 Python 3.11,重建"
  rm -rf "$ROOT/pio-venv"
fi
if [ ! -x "$ROOT/pio-venv/bin/pio" ]; then
  "$ROOT/python-3.11/bin/python3" -m venv "$ROOT/pio-venv"
  "$ROOT/pio-venv/bin/pip" install -q -i "$PIP_INDEX" "platformio==$PIO_VER"
fi
export PLATFORMIO_CORE_DIR=$ROOT/platformio
export PIP_INDEX_URL=$PIP_INDEX                      # 包的安装后脚本里也会调 pip
log "   $("$ROOT/pio-venv/bin/pio" --version)"
# 删掉装坏的包(没有清单文件的),下面会重装
for d in "$PLATFORMIO_CORE_DIR"/packages/*; do
  [ -d "$d" ] || continue
  if [ ! -f "$d/package.json" ]; then log "   删除装坏的包 $(basename "$d")"; rm -rf "$d"; fi
done
"$ROOT/pio-venv/bin/pio" pkg install -g -p "$PLATFORM_SPEC" 2>&1 | tail -1
"$ROOT/pio-venv/bin/pio" pkg install -g -t "$FRAMEWORK_SPEC" 2>&1 | tail -1
log "   平台:$(ls "$PLATFORMIO_CORE_DIR/platforms" | tr '\n' ' ')"
log "   包  :$(ls "$PLATFORMIO_CORE_DIR/packages" | tr '\n' ' ')"

# ---------------------------------------------------------------- 6. 固件凭据 secrets.h
# 固件编译要 secrets.h(WiFi、控制中心地址、板子 token),它不进 git,git archive 导不出来。
# ⚠️ 必须和 Mac 上编正式固件用的是同一份:token 不对的固件 OTA 上去,
#    板子心跳会被拒,之后就再也收不到 OTA 了,只能插线重烧。
log "6. 固件凭据 secrets.h"
REPO=${BUILD_REPO:-/root/esp32-s3-hid}
if [ ! -f "$ROOT/secrets.h" ]; then
  if [ -f "$REPO/secrets.h" ]; then
    install -m 600 "$REPO/secrets.h" "$ROOT/secrets.h"
    log "   从 $REPO/secrets.h 复制过来"
  else
    log "   ⚠️ 没有 $ROOT/secrets.h,固件构建会失败:把编正式固件用的 secrets.h 放到这里(chmod 600)"
  fi
fi
[ -f "$ROOT/secrets.h" ] && log "   sha256 $(sha256sum "$ROOT/secrets.h" | cut -c1-16)"

log "完成。磁盘占用:$(du -sh "$ROOT" | cut -f1)"
