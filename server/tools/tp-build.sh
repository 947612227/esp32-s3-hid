#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════
#  一次构建(由控制中心 src/builder.js 调用,也可以手工跑来排查)
#
#  参数全走环境变量:
#    KIND           app | firmware
#    SRC            源码目录(已经是 git archive 出来的副本,改它不影响仓库)
#    OUT            产物输出路径(完整文件名)
#    VERSION_NAME   App 的 versionName / 固件版本号
#    VERSION_CODE   App 的 versionCode(仅 app)
#    BUILD_ROOT     工具链目录(setup-build-env.sh 装的),默认 /opt/tp-build
#    BUILD_JOBS     固件并行编译数,默认 1(服务器只有 1.7G 内存)
#    NIMBLE_PIN     固件 NimBLE 库版本,默认 2.5.1(和 Mac 上实际编出 1.3.0 的一致)
#    SECRETS        固件凭据 secrets.h 的位置,默认 $BUILD_ROOT/secrets.h
#
#  ⚠️ 版本号只改 SRC 副本里的 AndroidManifest.xml / platformio.ini,
#     仓库里的代码一行不动。
# ══════════════════════════════════════════════════════════════════
set -euo pipefail

BUILD_ROOT=${BUILD_ROOT:-/opt/tp-build}
BUILD_JOBS=${BUILD_JOBS:-1}
NIMBLE_PIN=${NIMBLE_PIN:-2.5.1}
SECRETS=${SECRETS:-$BUILD_ROOT/secrets.h}

步骤() { echo "[$(date +%H:%M:%S)] $*"; }
失败() { echo "✗ $*" >&2; exit 1; }

[ -n "${KIND:-}" ] && [ -n "${SRC:-}" ] && [ -n "${OUT:-}" ] && [ -n "${VERSION_NAME:-}" ] \
  || 失败 "缺参数:KIND / SRC / OUT / VERSION_NAME 都要给"
[ -d "$SRC" ] || 失败 "源码目录不存在: $SRC"
# 版本号只允许安全字符(要拼进 sed 和 C 宏)
[[ "$VERSION_NAME" =~ ^[0-9A-Za-z._-]{1,32}$ ]] || 失败 "版本号含非法字符: $VERSION_NAME"
mkdir -p "$(dirname "$OUT")"

# ---------------------------------------------------------------- App
构建App() {
  [[ "${VERSION_CODE:-}" =~ ^[0-9]{1,9}$ ]] || 失败 "versionCode 不是数字: ${VERSION_CODE:-}"
  export JAVA_HOME=$BUILD_ROOT/jdk-17
  export PATH=$JAVA_HOME/bin:$PATH
  export ANDROID_SDK_ROOT=$BUILD_ROOT/android-sdk
  [ -x "$JAVA_HOME/bin/javac" ] || 失败 "没装 JDK,先跑 server/tools/setup-build-env.sh"

  local mf=$SRC/android-app/app/src/main/AndroidManifest.xml
  [ -f "$mf" ] || 失败 "找不到 $mf(这个分支没有 App 代码?)"

  # 1. 改副本里的版本号
  步骤 "1. 写入版本号 versionCode=$VERSION_CODE versionName=$VERSION_NAME"
  sed -i -E "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"$VERSION_CODE\"/; \
             s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" "$mf"
  grep -q "android:versionCode=\"$VERSION_CODE\"" "$mf" || 失败 "versionCode 没写进去"
  grep -q "android:versionName=\"$VERSION_NAME\"" "$mf" || 失败 "versionName 没写进去"

  # 2. 编译(仓库自带的 build.sh:aapt2 → javac → d8 → zipalign → apksigner)
  步骤 "2. 编译 APK"
  (cd "$SRC/android-app" && bash build.sh)
  # ⚠️ 开了 pipefail,不能用 `| head -1`(head 提前关管道 → 前面的命令 SIGPIPE 退出码 141)
  local apks=("$SRC"/android-app/build/*.apk)
  local apk=${apks[0]}
  [ -f "$apk" ] || 失败 "build.sh 跑完了但没找到签名后的 APK"

  # 3. 校验:包里的版本号、签名证书
  步骤 "3. 校验产物"
  local bts=("$ANDROID_SDK_ROOT"/build-tools/*/)
  local bt=${bts[${#bts[@]}-1]}
  local badging
  badging=$("$bt/aapt2" dump badging "$apk")
  badging=${badging%%$'\n'*}
  echo "   $badging"
  [[ "$badging" == *"versionCode='$VERSION_CODE'"* ]] || 失败 "APK 里的 versionCode 不对"
  [[ "$badging" == *"versionName='$VERSION_NAME'"* ]] || 失败 "APK 里的 versionName 不对"
  local certs
  certs=$("$bt/apksigner" verify --print-certs "$apk")
  echo "$certs" | grep -i "SHA-256" | sed 's/^/   /' || 失败 "APK 签名校验失败"

  cp "$apk" "$OUT"
}

# ---------------------------------------------------------------- 固件
构建固件() {
  export PLATFORMIO_CORE_DIR=$BUILD_ROOT/platformio
  export PATH=$BUILD_ROOT/pio-venv/bin:$PATH
  # 首次编译 PlatformIO 会按需装工具包,有的包装完要 pip 装依赖;服务器连 PyPI 慢,走阿里云
  export PIP_INDEX_URL=${PIP_INDEX_URL:-https://mirrors.aliyun.com/pypi/simple/}
  command -v pio >/dev/null || 失败 "没装 PlatformIO,先跑 server/tools/setup-build-env.sh"

  local ini=$SRC/platformio.ini
  [ -f "$ini" ] || 失败 "找不到 $ini"

  # 1. 改副本里的固件版本号(-DFW_VERSION=\"x.y.z\")
  步骤 "1. 写入固件版本号 $VERSION_NAME"
  grep -q 'FW_VERSION=' "$ini" || 失败 "platformio.ini 里没有 FW_VERSION(这个分支不支持构建时注入版本)"
  sed -i -E "s/-DFW_VERSION=\\\\\"[^\\\\]*\\\\\"/-DFW_VERSION=\\\\\"$VERSION_NAME\\\\\"/" "$ini"
  grep -q "FW_VERSION=\\\\\"$VERSION_NAME\\\\\"" "$ini" || 失败 "版本号没写进 platformio.ini"
  grep 'FW_VERSION=' "$ini" | sed 's/^/   /'

  # 凭据文件不进 git,单独放在服务器上(setup-build-env.sh 第 6 步)
  [ -f "$SECRETS" ] || 失败 "缺少 $SECRETS(WiFi / 控制中心地址 / 板子 token),见 setup-build-env.sh 第 6 步"
  cp "$SECRETS" "$SRC/secrets.h"
  echo "   secrets.h sha256 $(sha256sum "$SECRETS" | cut -c1-16)"

  cd "$SRC"
  # 2. 锁定 NimBLE 版本(lib_deps 写的是 ^2.x,不锁的话首次会装最新版,和 Mac 编出来的不一样)
  if [ ! -d .pio/libdeps/ble/NimBLE-Arduino ]; then
    步骤 "2. 安装 NimBLE-Arduino@$NIMBLE_PIN(只装进这个工作目录,不改 platformio.ini)"
    pio pkg install -e ble --no-save -l "h2zero/NimBLE-Arduino@$NIMBLE_PIN"
  else
    步骤 "2. NimBLE 已装: $(grep -m1 '^version' .pio/libdeps/ble/NimBLE-Arduino/library.properties || echo '版本未知')"
  fi

  # 3. 编译 ble 环境(OTA 用的就是它的 firmware.bin)
  步骤 "3. 编译固件(pio run -e ble -j $BUILD_JOBS)"
  pio run -e ble -j "$BUILD_JOBS"
  local bin=.pio/build/ble/firmware.bin
  [ -f "$bin" ] || 失败 "编译完了但没有 $bin"

  # 4. 校验:ESP32 镜像头魔数 0xE9,且版本号字符串确实编进去了
  步骤 "4. 校验产物"
  [ "$(od -An -tx1 -N1 "$bin" | tr -d ' ')" = "e9" ] || 失败 "firmware.bin 不是 ESP32 镜像(头字节不是 0xE9)"
  grep -aq "$VERSION_NAME" "$bin" || 失败 "firmware.bin 里找不到版本号 $VERSION_NAME"
  echo "   镜像头 OK,包含版本号 $VERSION_NAME"

  cp "$bin" "$OUT"

  # 5. 整片刷机(后台「USB 刷机」)还要另外三段:启动程序 / 分区表 / boot_app0
  #    地址和参数与 `pio run -e ble -t upload -v` 打出来的 esptool 命令一致
  #    (2026-10-15 实测:--flash_mode dio --flash_freq 80m --flash_size 8MB
  #     0x0 bootloader.bin 0x8000 partitions.bin 0xe000 boot_app0.bin 0x10000 firmware.bin)
  步骤 "5. 收集整片刷机用的文件"
  local parts=$(dirname "$OUT")/parts
  local app0=$PLATFORMIO_CORE_DIR/packages/framework-arduinoespressif32/tools/partitions/boot_app0.bin
  local fsize
  fsize=$(sed -n -E 's/^board_build\.flash_size *= *([0-9]+MB).*/\1/p' "$ini" | head -1)
  [ -n "$fsize" ] || 失败 "platformio.ini 里没有 board_build.flash_size"
  mkdir -p "$parts"
  for f in .pio/build/ble/bootloader.bin .pio/build/ble/partitions.bin "$app0"; do
    [ -f "$f" ] || 失败 "缺少 $f"
    cp "$f" "$parts/"
  done
  cat > "$parts/flash.json" <<JSON
{
  "chip": "esp32s3",
  "flashMode": "dio",
  "flashFreq": "80m",
  "flashSize": "$fsize",
  "files": [
    { "name": "bootloader", "file": "bootloader.bin", "address": 0 },
    { "name": "partitions", "file": "partitions.bin", "address": 32768 },
    { "name": "boot_app0",  "file": "boot_app0.bin",  "address": 57344 },
    { "name": "app",        "file": "app",            "address": 65536 }
  ]
}
JSON
  echo "   $(ls "$parts" | tr '\n' ' ')(Flash $fsize)"
}

case "$KIND" in
  app) 构建App ;;
  firmware) 构建固件 ;;
  *) 失败 "KIND 只能是 app 或 firmware: $KIND" ;;
esac

步骤 "完成:$(basename "$OUT") $(stat -c %s "$OUT") 字节"
步骤 "sha256 $(sha256sum "$OUT" | cut -d' ' -f1)"
