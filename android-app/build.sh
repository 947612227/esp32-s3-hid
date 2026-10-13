#!/usr/bin/env bash
# ============================================================================
#  build.sh —— 纯命令行编译 Android APK(不需要 Gradle / Android Studio)
#
#  为什么不用 Gradle:它要下载几百 MB 依赖,而这个 App 只有 2 个 Java 文件、
#  界面全部用代码构建,用 SDK 自带的工具链直接编译更快也更可控。
#
#  流程:aapt2 编译资源 → aapt2 链接(生成 R.java + 资源清单)
#        → javac 编译 → d8 转 dex → 打包 → zipalign 对齐 → apksigner 签名
#
#  用法:
#    ./build.sh              编译并签名,输出 build/触摸注入板.apk
#    ./build.sh --install    编译后顺便 adb 安装到手机
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

# ---------------------------------------------------------------- 定位 SDK
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/app/android_sdk}}"
[ -d "$SDK" ] || { echo "✗ 找不到 Android SDK,请设置 ANDROID_SDK_ROOT"; exit 1; }

BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] && [ -n "$PLATFORM" ] || { echo "✗ 缺少 build-tools 或 platforms"; exit 1; }

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
ANDROID_JAR="$PLATFORM/android.jar"

echo "SDK      : $SDK"
echo "build-tools: $(basename "$BT")"
echo "platform : $(basename "$PLATFORM")"
echo

# ---------------------------------------------------------------- 目录准备
SRC="app/src/main"
OUT="build"
rm -rf "$OUT"
mkdir -p "$OUT"/{res-c,gen,classes,dex,apk}

MANIFEST="$SRC/AndroidManifest.xml"
RES_DIR="$SRC/res"
JAVA_SRC="$SRC/java"

# ---------------------------------------------------------------- 1. 编译资源
echo "[1/6] aapt2 compile 资源"
# --dir 会自动扫描整个 res 目录;此时不能再单独给文件列表
"$AAPT2" compile -o "$OUT/res-c/" --dir "$RES_DIR" 2>&1 | sed 's/^/      /' || true
FLATS=$(find "$OUT/res-c" -name '*.flat' | sort)
if [ -z "$FLATS" ]; then echo "      ✗ 没有编译出任何资源"; exit 1; fi
echo "      生成 $(echo "$FLATS" | wc -l | tr -d ' ') 个 .flat"

# ---------------------------------------------------------------- 2. 链接资源
echo "[2/6] aapt2 link(生成 R.java)"
"$AAPT2" link \
  -o "$OUT/apk/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  --min-sdk-version 21 \
  --target-sdk-version 35 \
  --version-code 1 --version-name 1.0 \
  $FLATS 2>&1 | sed 's/^/      /'

# ---------------------------------------------------------------- 3. 编译 Java
echo "[3/6] javac 编译"
# 平台自带 android.jar,不需要额外依赖;源码保持 Java 7 兼容写法
find "$JAVA_SRC" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# ⚠️ 编译错误必须中断!之前用 `|| true` 把错误吞掉了,
#    结果 d8 拿旧的 .class 打出一个缺类的坏包,还不报错。实测踩过。
#    这里先把 javac 输出落盘再打印,避免管道把错误信息吞掉。
# ⚠️ 必须先关掉 set -e,否则 javac 一失败脚本就直接退出,
#    下面的错误打印根本不会执行(实测踩过)。
set +e
javac -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
set -e
if [ $JAVAC_RC -ne 0 ]; then
  echo "      ── javac 错误 ──────────────────────────────"
  cat "$OUT/javac.log"
  echo "      ────────────────────────────────────────────"
  echo "      ✗ javac 编译失败(退出码 $JAVAC_RC),已中止"
  exit 1
fi
grep -v "bootstrap class path" "$OUT/javac.log" | sed 's/^/      /' || true

if [ ! -f "$OUT/classes/com/dsh/touchpad/MainActivity.class" ]; then
  echo "      ✗ 没有产出 MainActivity.class,编译异常,已中止"
  exit 1
fi

# ---------------------------------------------------------------- 4. d8 转 dex
echo "[4/6] d8 生成 dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$D8" --release --min-api 21 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" @"$OUT/classes.txt" 2>&1 | sed 's/^/      /'

# ---------------------------------------------------------------- 5. 打包
echo "[5/6] 打包 APK"
cp "$OUT/apk/base.apk" "$OUT/apk/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$HERE/$OUT/apk/unsigned.apk" classes*.dex )
"$ZIPALIGN" -f -p 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

# ---------------------------------------------------------------- 6. 签名
echo "[6/6] 签名"
KS="$HERE/debug.keystore"
if [ ! -f "$KS" ]; then
  echo "      生成调试签名(只用于自用安装)"
  keytool -genkeypair -v -keystore "$KS" -alias touchpad \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=TouchPad Debug, OU=DSH, O=DSH, L=NA, S=NA, C=CN" >/dev/null 2>&1
fi

APK="$OUT/触摸注入板.apk"
"$APKSIGNER" sign \
  --ks "$KS" --ks-key-alias touchpad \
  --ks-pass pass:android --key-pass pass:android \
  --out "$APK" "$OUT/apk/aligned.apk"

"$APKSIGNER" verify --print-certs "$APK" | head -3 | sed 's/^/      /'

echo
echo "✅ 编译完成: $APK"
ls -lh "$APK" | awk '{print "   大小: " $5}'

# ---------------------------------------------------------------- 可选安装
if [ "${1:-}" = "--install" ]; then
  ADB="${ADB:-$SDK/platform-tools/adb}"
  echo
  echo "安装到手机…"
  "$ADB" install -r "$APK" && echo "✅ 已安装,在手机上找「触摸注入板」"
fi
