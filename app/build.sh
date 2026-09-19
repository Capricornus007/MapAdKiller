#!/bin/bash
# MapAdKiller 模組 Linux 端建置（自 build.ps1 移植，libxposed API 102，離線工具鏈）
# 用法: bash build.sh   （在 app/ 目錄下，或給絕對路徑）
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-35/android.jar"
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$APP/dist"; REL="$APP/../releases"
VER="${1:-1.0.7-fork}"

# 全 ASCII 暫存目錄（非 ASCII 路徑會坑 aapt2/d8）
SRC="$(mktemp -d /tmp/makb.XXXXXX)"
trap 'rm -rf "$SRC"' EXIT
mkdir -p "$SRC/build/stubs" "$SRC/build/classes" "$SRC/build/dex" "$SRC/build/out"

cp -a "$APP/stub-src" "$APP/src" "$APP/res" "$APP/META-INF" "$APP/libs" "$SRC/"
cp "$APP/AndroidManifest.xml" "$SRC/"

echo "[1/6] javac libxposed stubs"
find "$SRC/stub-src" -name '*.java' > "$SRC/stub.list"
javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath "$AJ" -d "$SRC/build/stubs" @"$SRC/stub.list"

echo "[2/6] javac module sources"
find "$SRC/src" -name '*.java' > "$SRC/src.list"
javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath "$AJ" \
  -classpath "$SRC/build/stubs:$SRC/libs/service-classes.jar" -d "$SRC/build/classes" @"$SRC/src.list"

echo "[3/6] d8 -> classes.dex"
jar cf "$SRC/build/classes.jar" -C "$SRC/build/classes" .
cp "$SRC/libs/service-classes.jar" "$SRC/build/service-classes.jar"
"$BT/d8" --min-api 26 --lib "$AJ" --output "$SRC/build/dex" \
  "$SRC/build/classes.jar" "$SRC/build/service-classes.jar"
[ -f "$SRC/build/dex/classes.dex" ] || { echo "d8 failed"; exit 1; }

echo "[4/6] aapt2 compile+link + 塞 dex/META-INF"
"$BT/aapt2" compile --dir "$SRC/res" -o "$SRC/build/res.zip"
"$BT/aapt2" link -o "$SRC/build/out/module.apk" --manifest "$SRC/AndroidManifest.xml" \
  -I "$AJ" --min-sdk-version 26 --target-sdk-version 34 "$SRC/build/res.zip"
cp "$SRC/build/dex/classes.dex" "$SRC/build/out/classes.dex"
cp -a "$SRC/META-INF" "$SRC/build/out/META-INF"
( cd "$SRC/build/out" && zip -q module.apk classes.dex && zip -qr module.apk META-INF )

echo "[5/6] zipalign + sign v1/v2/v3"
"$BT/zipalign" -f 4 "$SRC/build/out/module.apk" "$SRC/build/out/aligned.apk"
KS="$APP/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias mapadkiller -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=MapAdKiller, OU=ENI, O=ENI, L=NA, S=NA, C=CN"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$SRC/build/out/MapAdKiller.apk" "$SRC/build/out/aligned.apk"
"$BT/apksigner" verify --print-certs "$SRC/build/out/MapAdKiller.apk" | head -4

echo "[6/6] dist"
mkdir -p "$OUT" "$REL"
cp "$SRC/build/out/MapAdKiller.apk" "$OUT/MapAdKiller.apk"
cp "$SRC/build/out/MapAdKiller.apk" "$REL/MapAdKiller-v$VER.apk"
echo "產物: $REL/MapAdKiller-v$VER.apk ($(stat -c %s "$REL/MapAdKiller-v$VER.apk") bytes)"
