#!/usr/bin/env bash
# Run mirror VD integration tests on a connected device.
#
# Usage:
#   ./tests/run_mirror_vd_test.sh              # auto-detect device
#   ./tests/run_mirror_vd_test.sh -s SERIAL    # specific device
#
# Prerequisites:
#   - Standard flavor APK installed (ai.opencyvis.standard)
#   - FLAG_SECURE demo APK installed (ai.opencyvis.test.flagsecure)
#   - Build both:
#       cd android && ./gradlew :app:assembleStandardDebug :flagsecure-demo:assembleDebug

set -euo pipefail

SERIAL_FLAG=""
if [[ "${1:-}" == "-s" && -n "${2:-}" ]]; then
    SERIAL_FLAG="-s $2"
fi

adb() { command adb $SERIAL_FLAG "$@"; }

# ── Resolve APK path ──
PKG="ai.opencyvis.standard"
APK_PATH=$(adb shell pm path "$PKG" 2>/dev/null | head -1 | sed 's/package://')
if [[ -z "$APK_PATH" ]]; then
    # Fall back to non-standard package name
    PKG="ai.opencyvis"
    APK_PATH=$(adb shell pm path "$PKG" 2>/dev/null | head -1 | sed 's/package://')
fi

if [[ -z "$APK_PATH" ]]; then
    echo "ERROR: OpenCyvis not installed. Run:"
    echo "  cd android && ./gradlew :app:assembleStandardDebug"
    echo "  adb install app/build/outputs/apk/standard/debug/app-standard-debug.apk"
    exit 1
fi

# ── Check FLAG_SECURE demo app ──
DEMO_INSTALLED=$(adb shell pm path ai.opencyvis.test.flagsecure 2>/dev/null || true)
if [[ -z "$DEMO_INSTALLED" ]]; then
    echo "WARNING: FLAG_SECURE demo not installed. Tests 5-6 will fail."
    echo "  cd android && ./gradlew :flagsecure-demo:assembleDebug"
    echo "  adb install tests/flagsecure-demo/build/outputs/apk/debug/flagsecure-demo-debug.apk"
fi

echo "── Mirror VD Integration Test ──"
echo "Package : $PKG"
echo "APK     : $APK_PATH"
echo ""

# ── Clear logcat and run ──
adb logcat -c

adb shell "CLASSPATH=$APK_PATH app_process /system/bin ai.opencyvis.backend.MirrorVdTest" 2>&1 || true

# ── Parse results from logcat ──
echo ""
echo "── Results ──"
adb logcat -d -s "MirrorVdTest:*" | grep -E "PASS|FAIL|Results|PASSED|FAILED|═══" | while IFS= read -r line; do
    echo "$line" | sed 's/.*MirrorVdTest: //'
done

# ── Exit code ──
if adb logcat -d -s "MirrorVdTest:*" | grep -q "ALL TESTS PASSED"; then
    echo ""
    echo "EXIT: 0 (all tests passed)"
    exit 0
else
    echo ""
    echo "EXIT: 1 (test failures detected)"
    exit 1
fi
