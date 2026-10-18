#!/bin/bash
# Build, install, and configure OpenCyvis on connected device.
# Reads API config from ~/.config/opencyvis/.env (qwen section, falls back to doubao).

set -e

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$HOME/.config/opencyvis/.env"
PACKAGE="ai.opencyvis"

# Usage: deploy.sh [provider]
# provider: qwen (default), anthropic, doubao, hunyuan
TARGET_SECTION="${1:-qwen}"

# --- Parse config from .env ---
parse_env() {
    local target="$1"
    local section=""
    local api_key="" base_url="" model=""

    while IFS= read -r line; do
        line="${line%%#*}"           # strip comments
        line="$(echo "$line" | xargs)"  # trim whitespace
        [[ -z "$line" ]] && continue

        if [[ "$line" =~ ^\[(.+)\]$ ]]; then
            section="${BASH_REMATCH[1]}"
            continue
        fi

        if [[ "$section" == "$target" ]]; then
            key="${line%%:*}"
            val="${line#*:}"
            key="$(echo "$key" | xargs)"
            val="$(echo "$val" | xargs)"
            case "$key" in
                api_key)  api_key="$val" ;;
                base_url) base_url="$val" ;;
                model)    model="$val" ;;
            esac
        fi
    done < "$ENV_FILE"

    echo "$api_key|$base_url|$model"
}

make_config_uri() {
    python3 - "$API_PROVIDER" "$API_KEY" "$BASE_URL" "$MODEL" "20" <<'PY'
import sys
from urllib.parse import urlencode

provider, api_key, base_url, model, max_steps = sys.argv[1:]
params = {
    "provider": provider,
    "api_key": api_key,
    "base_url": base_url,
    "model": model,
    "max_steps": max_steps,
}
print("opencyvis://config?" + urlencode(params))
PY
}

shell_quote() {
    python3 - "$1" <<'PY'
import shlex
import sys

print(shlex.quote(sys.argv[1]))
PY
}

# --- Build ---
echo "==> Building..."
cd "$SCRIPT_DIR"
./gradlew assembleDebug -q

# --- Install ---
echo "==> Installing..."
adb install -r -g app/build/outputs/apk/debug/app-debug.apk

# --- Push config ---
if [[ ! -f "$ENV_FILE" ]]; then
    echo "WARNING: $ENV_FILE not found, skipping config push"
    exit 0
fi

echo "==> Reading config from $ENV_FILE [$TARGET_SECTION]..."
IFS='|' read -r API_KEY BASE_URL MODEL <<< "$(parse_env "$TARGET_SECTION")"

# Determine api_provider value
case "$TARGET_SECTION" in
    anthropic) API_PROVIDER="anthropic" ;;
    ollama)    API_PROVIDER="ollama" ;;
    *)         API_PROVIDER="openai" ;;
esac

if [[ "$API_PROVIDER" != "ollama" && -z "$API_KEY" ]]; then
    echo "WARNING: No api_key found in $ENV_FILE"
    exit 0
fi

# Provider-specific defaults
if [[ "$API_PROVIDER" == "ollama" ]]; then
    MODEL="${MODEL:-gemma4:31b-it-q4_K_M}"
    # Use 10.0.2.2 for emulator (maps to host localhost), otherwise localhost
    if adb shell getprop ro.hardware 2>/dev/null | grep -q "ranchu\|goldfish"; then
        BASE_URL="${BASE_URL:-http://10.0.2.2:11434}"
    else
        BASE_URL="${BASE_URL:-http://localhost:11434}"
    fi
    API_KEY="${API_KEY:-unused}"
else
    MODEL="${MODEL:-qwen3.5-plus-2026-02-15}"
    BASE_URL="${BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode/v1}"
fi

echo "==> Importing config via deeplink (provider=$API_PROVIDER)..."
CONFIG_URI="$(make_config_uri)"
adb shell am force-stop "$PACKAGE" 2>/dev/null
adb shell "am start -a android.intent.action.VIEW -d $(shell_quote "$CONFIG_URI") -p $PACKAGE"

echo "==> Done!"
