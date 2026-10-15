#!/usr/bin/env bash
# Builds NeonMaze.apk with the bare Android SDK tools (no Gradle).
# Usage: ./build.sh [--install]
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-/opt/android-sdk}
BT=${BUILD_TOOLS:-$SDK/build-tools/36.0.0}
PLATFORM=${PLATFORM:-$SDK/platforms/android-35/android.jar}
JAVAC=${JAVAC:-/usr/lib/jvm/java-17-openjdk/bin/javac}
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "› resources"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" --manifest AndroidManifest.xml \
    --java build/gen --min-sdk-version 26 --target-sdk-version 35 \
    --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-1.0}" build/res.zip

echo "› javac"
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -Xlint:-options -nowarn \
    -bootclasspath "$PLATFORM:$BT/core-lambda-stubs.jar" \
    -d build/classes $(find src build/gen -name '*.java')

echo "› d8"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output build/dex $(find build/classes -name '*.class')

echo "› package + sign"
cp build/base.apk build/unaligned.apk
(cd build/dex && zip -q ../unaligned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unaligned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out build/NeonMaze.apk build/aligned.apk
echo "✓ build/NeonMaze.apk"

if [[ "${1:-}" == "--install" ]]; then
    adb install -r build/NeonMaze.apk
    adb shell am start -n com.neonmaze/.MainActivity
fi
