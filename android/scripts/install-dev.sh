#!/bin/bash
# Build, sign, and install to emulator for development
# Usage: ./scripts/install-dev.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PACKAGE="ai.opencyvis"

echo "=== Building debug APK ==="
cd "$ANDROID_DIR"

if [[ ! -f "gradlew" ]]; then
    echo "Error: gradlew not found in $ANDROID_DIR"
    exit 1
fi

./gradlew assembleDebug

# Find the built APK
DEBUG_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$DEBUG_APK" ]]; then
    echo "Error: Debug APK not found at $DEBUG_APK"
    echo "Check Gradle build output for errors."
    exit 1
fi

echo ""
echo "=== Signing with platform key ==="
SIGNED_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug-signed.apk"
"$SCRIPT_DIR/sign-apk.sh" "$DEBUG_APK" "$SIGNED_APK"

echo ""
echo "=== Checking for connected device ==="
if ! adb devices | grep -q "device$"; then
    echo "Error: No device/emulator connected."
    echo "Start an emulator or connect a device, then retry."
    exit 1
fi

echo ""
echo "=== Installing APK ==="
adb install -r -g "$SIGNED_APK"

echo ""
echo "=== Restarting app ==="
adb shell am force-stop "$PACKAGE" 2>/dev/null || true
adb shell am start -n "$PACKAGE/.MainActivity"

echo ""
echo "Done. $PACKAGE is running on device."
