#!/bin/bash
# Sign APK with AOSP platform key for system app privileges
# Usage: ./scripts/sign-apk.sh <unsigned.apk> [output.apk]
#
# Requires:
#   - AOSP_ROOT env var pointing to AOSP source tree
#   - apksigner (from Android SDK build-tools)
#
# The platform key pair is at:
#   $AOSP_ROOT/build/target/product/security/platform.{pk8,x509.pem}
#
# Alternatively, place a local key pair in android/platform-key/:
#   android/platform-key/platform.pk8
#   android/platform-key/platform.x509.pem

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

INPUT_APK="${1:-}"
OUTPUT_APK="${2:-}"

if [[ -z "$INPUT_APK" ]]; then
    echo "Error: No input APK specified."
    echo "Usage: $0 <unsigned.apk> [output.apk]"
    exit 1
fi

if [[ ! -f "$INPUT_APK" ]]; then
    echo "Error: Input APK not found: $INPUT_APK"
    exit 1
fi

# Default output: replace -unsigned with -signed, or append -signed
if [[ -z "$OUTPUT_APK" ]]; then
    if [[ "$INPUT_APK" == *"-unsigned"* ]]; then
        OUTPUT_APK="${INPUT_APK/-unsigned/-signed}"
    else
        OUTPUT_APK="${INPUT_APK%.apk}-signed.apk"
    fi
fi

# Locate platform key pair
PLATFORM_PK8=""
PLATFORM_X509=""

# Priority 1: Local key pair in android/platform-key/
LOCAL_KEY_DIR="$ANDROID_DIR/platform-key"
if [[ -f "$LOCAL_KEY_DIR/platform.pk8" && -f "$LOCAL_KEY_DIR/platform.x509.pem" ]]; then
    PLATFORM_PK8="$LOCAL_KEY_DIR/platform.pk8"
    PLATFORM_X509="$LOCAL_KEY_DIR/platform.x509.pem"
    echo "Using local platform key from $LOCAL_KEY_DIR"
# Priority 2: AOSP_ROOT environment variable
elif [[ -n "${AOSP_ROOT:-}" ]]; then
    AOSP_KEY_DIR="$AOSP_ROOT/build/target/product/security"
    if [[ -f "$AOSP_KEY_DIR/platform.pk8" && -f "$AOSP_KEY_DIR/platform.x509.pem" ]]; then
        PLATFORM_PK8="$AOSP_KEY_DIR/platform.pk8"
        PLATFORM_X509="$AOSP_KEY_DIR/platform.x509.pem"
        echo "Using AOSP platform key from $AOSP_KEY_DIR"
    else
        echo "Error: Platform key not found in AOSP_ROOT at $AOSP_KEY_DIR"
        echo "Expected files: platform.pk8, platform.x509.pem"
        exit 1
    fi
else
    echo "Error: No platform key found."
    echo ""
    echo "Provide a key pair using one of these methods:"
    echo "  1. Place platform.pk8 and platform.x509.pem in $LOCAL_KEY_DIR/"
    echo "  2. Set AOSP_ROOT to point to your AOSP source tree"
    exit 1
fi

# Locate apksigner
if command -v apksigner &>/dev/null; then
    APKSIGNER="apksigner"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
    # Find the latest build-tools version
    LATEST_BT=$(ls -1 "$ANDROID_HOME/build-tools/" 2>/dev/null | sort -V | tail -1)
    if [[ -n "$LATEST_BT" && -x "$ANDROID_HOME/build-tools/$LATEST_BT/apksigner" ]]; then
        APKSIGNER="$ANDROID_HOME/build-tools/$LATEST_BT/apksigner"
    fi
fi

if [[ -z "${APKSIGNER:-}" ]]; then
    echo "Error: apksigner not found."
    echo "Install Android SDK build-tools or set ANDROID_HOME."
    exit 1
fi

echo "Signing $INPUT_APK -> $OUTPUT_APK"

"$APKSIGNER" sign \
    --key "$PLATFORM_PK8" \
    --cert "$PLATFORM_X509" \
    --out "$OUTPUT_APK" \
    "$INPUT_APK"

echo "Verifying signature..."
"$APKSIGNER" verify --verbose "$OUTPUT_APK"

echo ""
echo "Signed APK: $OUTPUT_APK"
