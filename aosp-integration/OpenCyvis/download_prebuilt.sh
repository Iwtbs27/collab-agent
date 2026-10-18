#!/bin/bash
set -euo pipefail

REPO="opencyvis/opencyvis-phone"
APK_NAME="OpenCyvis.apk"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT="$SCRIPT_DIR/$APK_NAME"

VERSION="${1:-latest}"

if [ "$VERSION" = "latest" ]; then
    echo "Fetching latest release from $REPO ..."
    URL=$(curl -sL "https://api.github.com/repos/$REPO/releases/latest" \
        | grep "browser_download_url.*opencyvis-system-release\.apk" \
        | head -1 \
        | cut -d '"' -f 4)
else
    echo "Fetching release $VERSION from $REPO ..."
    URL=$(curl -sL "https://api.github.com/repos/$REPO/releases/tags/$VERSION" \
        | grep "browser_download_url.*opencyvis-system-release\.apk" \
        | head -1 \
        | cut -d '"' -f 4)
fi

if [ -z "$URL" ]; then
    echo "ERROR: Could not find APK download URL for version '$VERSION'"
    echo "Available releases: https://github.com/$REPO/releases"
    exit 1
fi

echo "Downloading $URL ..."
curl -L -o "$OUTPUT" "$URL"

echo "Saved to $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
