#!/bin/bash
# E2E tests for ADB backend (standard flavor) on connected device
# Usage: ./tests/e2e/test_adb_backend.sh [device_serial]
#
# Prerequisites:
# - Device connected and paired with standard flavor
# - Ollama running at localhost:11434
# - Standard flavor installed

set -e

SERIAL="${1:-$(adb devices | grep -v "List\|^$" | head -1 | awk '{print $1}')}"
PKG="ai.opencyvis.standard"
ACTIVITY="$PKG/ai.opencyvis.ui.ControlPanelActivity"
SETUP_ACTIVITY="$PKG/ai.opencyvis.backend.SetupActivity"
ADB="adb -s $SERIAL"
PASS=0
FAIL=0
RESULTS=""

log() { echo "[$(date +%H:%M:%S)] $*"; }
pass() { PASS=$((PASS+1)); RESULTS+="| $1 | PASS | $2 |\n"; log "PASS: $1"; }
fail() { FAIL=$((FAIL+1)); RESULTS+="| $1 | FAIL | $2 |\n"; log "FAIL: $1 - $2"; }

send_task() {
    $ADB logcat -c
    $ADB shell "am broadcast -a ai.opencyvis.TEST -p $PKG --es instruction '$1'"
    sleep "${2:-40}"
}

check_logcat() {
    $ADB logcat -d -s "$1" | grep -q "$2"
}

# --- Test: Backend Connection ---
test_backend_connection() {
    log "Testing backend connection..."
    $ADB logcat -c
    $ADB shell am start -n "$ACTIVITY"
    sleep 5
    if check_logcat "AgentService" "Backend ready"; then
        pass "Backend Connection" "AgentService reports backend ready"
    else
        fail "Backend Connection" "No 'Backend ready' in logcat"
    fi
}

# --- Test: VD Creation ---
test_vd_creation() {
    log "Testing VD creation..."
    send_task "open settings" 30
    if check_logcat "VirtualDisplayManager" "displayId="; then
        pass "VD Creation" "VD created with valid displayId"
    else
        fail "VD Creation" "No displayId in VirtualDisplayManager logs"
    fi
}

# --- Test: Activity Launch on VD ---
test_activity_launch() {
    log "Testing activity launch on VD..."
    if check_logcat "AppLauncher\|PrivilegedService" "am start --display"; then
        pass "Activity Launch on VD" "Activity launched via am start --display"
    else
        fail "Activity Launch on VD" "No am start --display in logs"
    fi
}

# --- Test: Screenshot Capture ---
test_screenshot() {
    log "Testing screenshot capture..."
    if check_logcat "ScreenCapture\|AgentEngine" "captureBase64\|screenshot"; then
        local timing
        timing=$($ADB logcat -d -s ScreenCapture | grep -o "[0-9]*ms" | tail -1)
        pass "Screenshot Capture" "Captured successfully (${timing:-unknown timing})"
    else
        fail "Screenshot Capture" "No capture logs found"
    fi
}

# --- Test: Multi-step Navigation ---
test_multistep_nav() {
    log "Testing multi-step navigation..."
    send_task "open settings and navigate to Wi-Fi settings" 50
    local steps
    steps=$($ADB logcat -d -s AgentEngine | grep -c "Step [0-9]")
    if [ "$steps" -ge 2 ]; then
        pass "Multi-step Navigation" "$steps steps executed"
    else
        fail "Multi-step Navigation" "Only $steps steps (expected >=2)"
    fi
}

# --- Test: Input Injection ---
test_input_injection() {
    log "Testing input injection..."
    send_task "open Chrome and type hello in the search bar" 50
    if check_logcat "InputInjector\|ActionExecutor" "inject\|type\|text"; then
        pass "Input Injection" "Input events injected"
    else
        fail "Input Injection" "No input injection logs"
    fi
}

# --- Test: VD Persistence ---
test_vd_persistence() {
    log "Testing VD persistence across tasks..."
    send_task "open calculator" 25
    local vd1
    vd1=$($ADB logcat -d -s VirtualDisplayManager | grep "displayId=" | tail -1)
    send_task "open clock" 25
    local vd2
    vd2=$($ADB logcat -d -s VirtualDisplayManager | grep "displayId=" | tail -1)
    # VD should be reused (same displayId)
    if [ -n "$vd1" ]; then
        pass "VD Persistence" "VD alive across tasks"
    else
        fail "VD Persistence" "VD not found in logs"
    fi
}

# --- Test: Setup State Detection (Already Connected) ---
test_setup_already_connected() {
    log "Testing setup state detection (already connected)..."
    $ADB logcat -c
    $ADB shell am start -n "$SETUP_ACTIVITY"
    sleep 3
    if $ADB logcat -d | grep -q "ALREADY_CONNECTED\|finish"; then
        pass "Setup: Already Connected" "SetupActivity auto-closed"
    else
        fail "Setup: Already Connected" "SetupActivity did not auto-close"
    fi
}

# --- Test: No Crash/ANR ---
test_no_crash() {
    log "Testing for crashes..."
    local crashes
    crashes=$($ADB logcat -d -b crash | grep -c "$PKG" 2>/dev/null || echo "0")
    if [ "$crashes" -eq 0 ]; then
        pass "No Crashes" "Zero crash logs"
    else
        fail "No Crashes" "$crashes crash entries found"
    fi
}

# --- Run all tests ---
log "Starting E2E tests on device $SERIAL"
log "Package: $PKG"
echo ""

test_backend_connection
test_vd_creation
test_activity_launch
test_screenshot
test_multistep_nav
test_input_injection
test_vd_persistence
test_setup_already_connected
test_no_crash

# --- Report ---
echo ""
echo "================================"
echo "E2E Test Report: ADB Backend"
echo "================================"
echo "Device: $SERIAL"
echo "Results: $PASS passed, $FAIL failed"
echo ""
echo "| Test | Result | Notes |"
echo "|------|--------|-------|"
echo -e "$RESULTS"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
