#!/data/data/com.termux/files/usr/bin/env bash
set -e

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_DIR"

echo "=== Building Android CarPlay FM Radio Native APK ==="

rm -rf build
mkdir -p build/gen build/obj build/bin libs

if [ ! -f libs/android.jar ]; then
    echo "[1/5] Downloading android.jar..."
    curl -sSL -o libs/android.jar "https://github.com/Sable/android-platforms/raw/master/android-30/android.jar"
fi

echo "[2/5] Generating R.java..."
aapt package -f -m \
    -J build/gen \
    -S res \
    -M AndroidManifest.xml \
    -I libs/android.jar

echo "[3/5] Compiling Java source files..."
javac -d build/obj \
    -classpath libs/android.jar \
    -sourcepath "src:build/gen" \
    $(find src build/gen -name "*.java")

echo "[4/5] Converting bytecode to DEX using d8..."
d8 --output build/bin --classpath libs/android.jar $(find build/obj -name "*.class")

echo "[5/7] Packaging APK with AAPT..."
aapt package -f \
    -M AndroidManifest.xml \
    -S res \
    -I libs/android.jar \
    -F build/app-unsigned.apk \
    build/bin

echo "[6/7] Zip-aligning APK..."
zipalign -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "[7/7] Signing APK with apksigner..."
if [ ! -f debug.keystore ]; then
    keytool -genkey -v \
        -keystore debug.keystore \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

apksigner sign \
    --ks debug.keystore \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out android-carplay-app.apk \
    build/app-aligned.apk

echo "=== BUILD SUCCESSFUL ==="
echo "APK Output Path: $APP_DIR/android-carplay-app.apk"
ls -lh android-carplay-app.apk
