#!/bin/bash
# Extract framework classes for hidden API compilation
# Usage: ./scripts/extract-framework-stubs.sh [AOSP_ROOT]
#
# Copies framework.jar from AOSP out/ directory and processes it
# into a stubs jar suitable for compileOnly dependency.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$ANDROID_DIR/app/libs"

AOSP_ROOT="${1:-${AOSP_ROOT:-}}"

if [[ -z "$AOSP_ROOT" ]]; then
    echo "Error: AOSP_ROOT not specified."
    echo "Usage: $0 [AOSP_ROOT]"
    echo "   or: AOSP_ROOT=/path/to/aosp $0"
    echo ""
    echo "--- Manual extraction ---"
    echo "If you don't have an AOSP build, you can extract framework-stubs.jar manually:"
    echo ""
    echo "  1. From an AOSP build:"
    echo "     cp \$AOSP_ROOT/out/target/common/obj/JAVA_LIBRARIES/framework_intermediates/classes.jar \\"
    echo "        $OUTPUT_DIR/framework-stubs.jar"
    echo ""
    echo "  2. From a running emulator or device:"
    echo "     adb pull /system/framework/framework.jar /tmp/framework.jar"
    echo "     # Note: On recent Android versions, framework.jar may contain .dex files"
    echo "     # and needs conversion. Consider using dex2jar:"
    echo "     d2j-dex2jar /tmp/framework.jar -o $OUTPUT_DIR/framework-stubs.jar"
    echo ""
    echo "  3. From Android SDK (limited, no hidden APIs):"
    echo "     cp \$ANDROID_HOME/platforms/android-<API>/android.jar \\"
    echo "        $OUTPUT_DIR/framework-stubs.jar"
    echo ""
    exit 1
fi

if [[ ! -d "$AOSP_ROOT" ]]; then
    echo "Error: AOSP_ROOT directory does not exist: $AOSP_ROOT"
    exit 1
fi

# Search for framework intermediates in AOSP build output
FRAMEWORK_JAR=""

# Try the intermediates classes.jar first (has all hidden APIs)
CANDIDATES=(
    "$AOSP_ROOT/out/target/common/obj/JAVA_LIBRARIES/framework_intermediates/classes.jar"
    "$AOSP_ROOT/out/target/common/obj/JAVA_LIBRARIES/framework-minus-apex_intermediates/classes.jar"
    "$AOSP_ROOT/out/soong/.intermediates/frameworks/base/framework/android_common/combined/framework.jar"
    "$AOSP_ROOT/out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/combined/framework-minus-apex.jar"
)

for candidate in "${CANDIDATES[@]}"; do
    if [[ -f "$candidate" ]]; then
        FRAMEWORK_JAR="$candidate"
        echo "Found framework jar: $FRAMEWORK_JAR"
        break
    fi
done

if [[ -z "$FRAMEWORK_JAR" ]]; then
    echo "Error: Could not find framework.jar in AOSP build output."
    echo ""
    echo "Searched locations:"
    for candidate in "${CANDIDATES[@]}"; do
        echo "  $candidate"
    done
    echo ""
    echo "Make sure you have completed an AOSP build first:"
    echo "  cd $AOSP_ROOT"
    echo "  source build/envsetup.sh"
    echo "  lunch <target>"
    echo "  make framework"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
cp "$FRAMEWORK_JAR" "$OUTPUT_DIR/framework-stubs.jar"

echo "Extracted framework-stubs.jar to $OUTPUT_DIR/framework-stubs.jar"
echo ""
echo "Add to app/build.gradle.kts as compileOnly dependency:"
echo '  dependencies {'
echo '      compileOnly(files("libs/framework-stubs.jar"))'
echo '  }'
