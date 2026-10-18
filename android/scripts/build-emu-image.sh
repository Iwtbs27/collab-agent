#!/bin/bash
# Build AOSP emulator image with OpenCyvis pre-installed as a system app.
#
# This script does everything from zero:
#   1. Sets up Docker build environment
#   2. Syncs AOSP source from TUNA mirror
#   3. Applies goldfish emulator patches
#   4. Copies OpenCyvis into the AOSP tree
#   5. Builds the emulator system image
#   6. Exports images and generates a launch script
#
# Prerequisites:
#   - Docker Desktop (with at least 48 GB RAM, 16 CPUs allocated)
#   - Android SDK (emulator + platform-tools on PATH, or ANDROID_HOME set)
#   - ~150 GB free disk space (AOSP source + build output)
#   - macOS (Apple Silicon) or Linux
#
# Usage:
#   ./scripts/build-emu-image.sh [--workspace /path/to/workspace]
#
# The workspace defaults to ./aosp-build-workspace. Set it to an SSD for
# faster builds:
#   ./scripts/build-emu-image.sh --workspace /Volumes/ssd/aosp-build

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$ANDROID_DIR/docker-compose.build.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"

# --- Defaults ---
WORKSPACE="$ANDROID_DIR/aosp-build-workspace"
AOSP_BRANCH="android-latest-release"
JOBS=4

# --- Parse args ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --workspace) WORKSPACE="$2"; shift 2 ;;
    --branch)    AOSP_BRANCH="$2"; shift 2 ;;
    --jobs)      JOBS="$2"; shift 2 ;;
    *)           echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# Resolve to absolute path (create if it doesn't exist yet)
if [[ ! -d "$WORKSPACE" ]]; then
  mkdir -p "$WORKSPACE"
fi
WORKSPACE="$(cd "$WORKSPACE" && pwd)"

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}==>${NC} $*"; }
warn()  { echo -e "${YELLOW}WARNING:${NC} $*"; }
error() { echo -e "${RED}ERROR:${NC} $*" >&2; }

# ============================================================
# Step 0: Prerequisites
# ============================================================
info "Checking prerequisites..."

check_cmd() {
  if ! command -v "$1" &>/dev/null; then
    error "$1 not found. $2"
    return 1
  fi
}

check_cmd docker "Install Docker Desktop: https://docker.com/products/docker-desktop"
check_cmd "docker" "Docker must be running." && docker info &>/dev/null || error "Docker daemon is not running. Start Docker Desktop."

# Check Android SDK
SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ "$(uname)" == "Linux" ]]; then
  SDK_ROOT="${ANDROID_HOME:-$HOME/Android/Sdk}"
fi
EMULATOR_BIN="$SDK_ROOT/emulator/emulator"
ADB_BIN="$SDK_ROOT/platform-tools/adb"

if [[ ! -x "$EMULATOR_BIN" ]]; then
  warn "emulator not found at $EMULATOR_BIN"
  warn "Install Android SDK command-line tools and run: sdkmanager emulator platform-tools"
fi

if [[ ! -x "$ADB_BIN" ]]; then
  warn "adb not found at $ADB_BIN"
fi

# Check Docker resources
info "Checking Docker resource allocation..."
DOCKER_MEM=$(docker system info --format '{{.MemTotal}}' 2>/dev/null || echo 0)
DOCKER_MEM_GB=$((DOCKER_MEM / 1073741824))
if [[ $DOCKER_MEM_GB -lt 40 ]]; then
  warn "Docker has ${DOCKER_MEM_GB}GB RAM allocated. Recommend at least 48GB."
  warn "Increase in Docker Desktop > Settings > Resources."
fi

# Check disk space
DISK_AVAIL=$(df -g "$WORKSPACE" 2>/dev/null | tail -1 | awk '{print $4}' || echo 0)
if [[ $DISK_AVAIL -lt 150 ]]; then
  warn "Only ${DISK_AVAIL}GB free on $(dirname "$WORKSPACE"). Recommend at least 150GB."
fi

info "Workspace: $WORKSPACE"
info "AOSP branch: $AOSP_BRANCH"
info "Build jobs: $JOBS"
echo

# ============================================================
# Step 1: Create workspace directories
# ============================================================
info "Creating workspace directories..."
mkdir -p "$WORKSPACE"/{src,ccache,out}

# ============================================================
# Step 2: Start Docker container
# ============================================================
info "Starting Docker build container..."
export WORKSPACE OPENCYVIS_SRC="$ANDROID_DIR"
$COMPOSE up -d --build

# Helper to run commands inside the container
run_in_docker() {
  $COMPOSE exec -T aosp-build bash -lc "$*"
}

# ============================================================
# Step 3: Initialize AOSP repo
# ============================================================
info "Initializing AOSP repo (branch: $AOSP_BRANCH)..."
run_in_docker "
set -euo pipefail
cd /work/aosp

if [ ! -d .repo/repo ]; then
  mkdir -p .repo
  git clone https://mirrors.tuna.tsinghua.edu.cn/git/git-repo .repo/repo
  git -C .repo/repo checkout stable
fi

if [ ! -d .repo/manifests ]; then
  ./.repo/repo/repo init \
    -u https://aosp.tuna.tsinghua.edu.cn/platform/manifest \
    -b '$AOSP_BRANCH' \
    --partial-clone \
    --no-use-superproject \
    --no-repo-verify
  echo 'Repo initialized.'
else
  echo 'Repo already initialized, skipping.'
fi
"

# ============================================================
# Step 4: Sync AOSP source
# ============================================================
info "Syncing AOSP source (this takes a long time, ~2-4 hours on first run)..."
run_in_docker "
set -euo pipefail
cd /work/aosp
exec ./.repo/repo/repo sync -c -j$JOBS --no-clone-bundle --no-tags
"

# ============================================================
# Step 5: Apply goldfish emulator patches
# ============================================================
info "Applying goldfish emulator patches..."

run_in_docker "
set -euo pipefail
cd /work/aosp

# Patch 1: Disable task snapshots to prevent mapper.ranchu DMA crashes
CONFIG=device/generic/goldfish/phone/overlay/frameworks/base/core/res/res/values/config.xml

if ! grep -q 'config_disableTaskSnapshots' \"\$CONFIG\"; then
  python3 - <<'PY'
from pathlib import Path

path = Path('/work/aosp/device/generic/goldfish/phone/overlay/frameworks/base/core/res/res/values/config.xml')
text = path.read_text()
needle = '''    <!-- Desktop mode is supported on the current device  -->
    <bool name=\"config_isDesktopModeSupported\">true</bool>
'''
replacement = needle + '''
    <!-- Disable task snapshots on goldfish to avoid mapper.ranchu DMA crashes -->
    <bool name=\"config_disableTaskSnapshots\">true</bool>
'''
if needle not in text:
    raise SystemExit('expected overlay anchor not found')
path.write_text(text.replace(needle, replacement, 1))
print('config_disableTaskSnapshots added')
PY
else
  echo 'config_disableTaskSnapshots already present'
fi

# Patch 2: Fix mapper.cpp to fall back to non-DMA readFromHost
MAPPER=device/generic/goldfish/hals/gralloc/mapper.cpp

if grep -q 'LOG_ALWAYS_FATAL_IF(!rcEnc->featureInfo()->hasReadColorBufferDma)' \"\$MAPPER\"; then
  echo 'Patching mapper.cpp: add non-DMA readFromHost fallback...'
  python3 - <<'PYMAP'
from pathlib import Path

path = Path('/work/aosp/device/generic/goldfish/hals/gralloc/mapper.cpp')
text = path.read_text()

old = '''            LOG_ALWAYS_FATAL_IF(!rcEnc->featureInfo()->hasReadColorBufferDma);
            rcEnc->bindDmaDirectly(cb.getBufferPtr(),
                                   getMmapedPhysAddr(cb.getMmapedOffset()));
            rcEnc->rcReadColorBufferDMA(rcEnc, cb.hostHandle,
                                        0, 0, metadata.width, metadata.height,
                                        metadata.glFormat, metadata.glType,
                                        cb.getBufferPtr(), cb.bufferSize);'''

new = '''            if (rcEnc->featureInfo()->hasReadColorBufferDma) {
                rcEnc->bindDmaDirectly(cb.getBufferPtr(),
                                       getMmapedPhysAddr(cb.getMmapedOffset()));
                rcEnc->rcReadColorBufferDMA(rcEnc, cb.hostHandle,
                                            0, 0, metadata.width, metadata.height,
                                            metadata.glFormat, metadata.glType,
                                            cb.getBufferPtr(), cb.bufferSize);
            } else {
                rcEnc->rcReadColorBuffer(rcEnc, cb.hostHandle,
                                         0, 0, metadata.width, metadata.height,
                                         metadata.glFormat, metadata.glType,
                                         cb.getBufferPtr());
            }'''

if old not in text:
    raise SystemExit('mapper.cpp: expected DMA-only block not found (already patched?)')
path.write_text(text.replace(old, new, 1))
print('mapper.cpp patched successfully')
PYMAP
else
  echo 'mapper.cpp already patched'
fi
"

# ============================================================
# Step 6: Copy OpenCyvis into AOSP tree
# ============================================================
info "Copying OpenCyvis app source into AOSP tree..."

run_in_docker "
set -euo pipefail
cd /work/aosp

# Create the target directory
mkdir -p packages/apps/OpenCyvis

# Only copy what the AOSP Soong build needs (no Gradle, no local config)
cp /work/opencyvis/app/Android.bp packages/apps/OpenCyvis/Android.bp
cp /work/opencyvis/product.mk packages/apps/OpenCyvis/product.mk
cp -r /work/opencyvis/privapp-permissions-opencyvis.xml packages/apps/OpenCyvis/
cp -r /work/opencyvis/app/src packages/apps/OpenCyvis/src
cp -r /work/opencyvis/app/libs packages/apps/OpenCyvis/libs 2>/dev/null || true

echo 'OpenCyvis source copied to packages/apps/OpenCyvis/'
find packages/apps/OpenCyvis/ -maxdepth 2 -type f
"

# ============================================================
# Step 7: Integrate OpenCyvis into AOSP build
# ============================================================
info "Integrating OpenCyvis into AOSP build system..."

run_in_docker "
set -euo pipefail
cd /work/aosp

# Add OpenCyvis to the product packages and permissions
PRODUCT_MK=build/make/target/product/aosp_product.mk
MARKER='# OpenCyvis system app'

if ! grep -q 'OpenCyvis' \"\$PRODUCT_MK\"; then
  echo '' >> \"\$PRODUCT_MK\"
  echo \"$MARKER\" >> \"\$PRODUCT_MK\"
  echo 'PRODUCT_PACKAGES += OpenCyvis' >> \"\$PRODUCT_MK\"
  echo 'PRODUCT_COPY_FILES += packages/apps/OpenCyvis/privapp-permissions-opencyvis.xml:\$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-opencyvis.xml' >> \"\$PRODUCT_MK\"
  echo 'Added OpenCyvis to PRODUCT_PACKAGES in aosp_product.mk'
else
  echo 'OpenCyvis already in PRODUCT_PACKAGES'
fi

# Show what we added
echo
echo '--- Current aosp_product.mk tail ---'
tail -10 \"\$PRODUCT_MK\"
"

# ============================================================
# Step 8: Build emulator image
# ============================================================
info "Building AOSP emulator image (this takes 2-6 hours)..."
info "You can monitor progress with: $COMPOSE exec aosp-build tail -f /work/aosp/out/build.log"

run_in_docker "
set -euo pipefail
cd /work/aosp

source build/envsetup.sh
lunch sdk_phone64_arm64

echo 'Starting build with $JOBS jobs...'
m -j$JOBS droid 2>&1 | tee out/build.log

echo
echo '=== Build complete ==='
ls -lh out/target/product/emu64a/*.img 2>/dev/null || echo 'No images found'
"

# ============================================================
# Step 9: Export images from Docker
# ============================================================
info "Exporting emulator images from Docker..."

EXPORT_DIR="$WORKSPACE/emu64a"
mkdir -p "$EXPORT_DIR"

for file in \
  advancedFeatures.ini \
  config.ini \
  encryptionkey.img \
  kernel-ranchu \
  ramdisk-qemu.img \
  system-qemu.img \
  vendor-qemu.img \
  userdata.img \
  system.img \
  vendor.img \
  vbmeta.img \
  super.img \
  system-qemu-config.txt
do
  $COMPOSE cp "aosp-build:/work/aosp/out/target/product/emu64a/$file" "$EXPORT_DIR/$file" 2>/dev/null || \
    warn "Could not export $file (may not exist in this build)"
done

info "Images exported to $EXPORT_DIR"
ls -lh "$EXPORT_DIR"

# ============================================================
# Step 10: Generate launch script
# ============================================================
info "Generating launch script..."

PLATFORM="$(uname -s)"
LAUNCH_SCRIPT="$EXPORT_DIR/launch.sh"

if [[ "$PLATFORM" == "Darwin" ]]; then
  cat > "$LAUNCH_SCRIPT" << 'LAUNCH_MACOS'
#!/bin/sh
# Launch OpenCyvis AOSP emulator on macOS (Apple Silicon)
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
EMULATOR="$SDK_ROOT/emulator/emulator"
GPU_MODE="${GPU_MODE:-host}"
MEMORY_MB="${MEMORY_MB:-6144}"
TARGET_SDK="${TARGET_SDK:-28}"
export ANDROID_EMU_ENABLE_CRASH_REPORTING=0

if [ ! -x "$EMULATOR" ]; then
  echo "emulator not found at $EMULATOR" >&2
  echo "Install Android SDK: sdkmanager emulator" >&2
  exit 1
fi

RUN="$SCRIPT_DIR/run"
mkdir -p "$RUN/system"

cp "$SCRIPT_DIR/advancedFeatures.ini" "$RUN/advancedFeatures.ini"
cp "$SCRIPT_DIR/config.ini" "$RUN/config.ini"
rm -f "$RUN/userdata-qemu.img" "$RUN/cache.img"

# Metadata shims for macOS emulator
cat > "$SCRIPT_DIR/system/build.prop" << EOF
ro.build.version.sdk=$TARGET_SDK
ro.product.cpu.abi=arm64-v8a
EOF
cat > "$SCRIPT_DIR/boot.prop" << EOF
ro.boot.qemu.avd_name=opencyvis-emu
EOF

# Disable gfxstream features that crash on Apple Silicon
{
  echo "Vulkan = off"
  echo "VulkanVirtualQueue = off"
  echo "VulkanQueueSubmitWithCommands = off"
  echo "VulkanBatchedDescriptorSetUpdate = off"
  echo "HardwareDecoder = off"
  echo "GLDirectMem = off"
  echo "HostComposition = off"
} >> "$RUN/advancedFeatures.ini"

echo "Launching emulator (GPU=$GPU_MODE, RAM=${MEMORY_MB}MB)..."
exec "$EMULATOR" \
  -no-metrics \
  -verbose \
  -show-kernel \
  -avd-arch arm64 \
  -sysdir "$SCRIPT_DIR" \
  -datadir "$RUN" \
  -kernel "$SCRIPT_DIR/kernel-ranchu" \
  -ramdisk "$SCRIPT_DIR/ramdisk-qemu.img" \
  -system "$SCRIPT_DIR/system-qemu.img" \
  -vendor "$SCRIPT_DIR/vendor-qemu.img" \
  -initdata "$SCRIPT_DIR/userdata.img" \
  -data "$RUN/userdata-qemu.img" \
  -cache "$RUN/cache.img" \
  -wipe-data \
  -no-snapshot \
  -gpu "$GPU_MODE" \
  -memory "$MEMORY_MB" \
  -feature -Vulkan \
  -feature -VulkanVirtualQueue \
  -feature -VulkanQueueSubmitWithCommands \
  -feature -VulkanBatchedDescriptorSetUpdate \
  -feature -HardwareDecoder \
  -feature -GLDirectMem \
  -feature -HostComposition
LAUNCH_MACOS
else
  cat > "$LAUNCH_SCRIPT" << 'LAUNCH_LINUX'
#!/bin/sh
# Launch OpenCyvis AOSP emulator on Linux
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
EMULATOR="$SDK_ROOT/emulator/emulator"
GPU_MODE="${GPU_MODE:-host}"
MEMORY_MB="${MEMORY_MB:-6144}"
export ANDROID_EMU_ENABLE_CRASH_REPORTING=0

if [ ! -x "$EMULATOR" ]; then
  echo "emulator not found at $EMULATOR" >&2
  exit 1
fi

RUN="$SCRIPT_DIR/run"
mkdir -p "$RUN"

cp "$SCRIPT_DIR/advancedFeatures.ini" "$RUN/advancedFeatures.ini"
cp "$SCRIPT_DIR/config.ini" "$RUN/config.ini"
rm -f "$RUN/userdata-qemu.img" "$RUN/cache.img"

exec "$EMULATOR" \
  -no-metrics \
  -avd-arch arm64 \
  -sysdir "$SCRIPT_DIR" \
  -datadir "$RUN" \
  -kernel "$SCRIPT_DIR/kernel-ranchu" \
  -ramdisk "$SCRIPT_DIR/ramdisk-qemu.img" \
  -system "$SCRIPT_DIR/system-qemu.img" \
  -vendor "$SCRIPT_DIR/vendor-qemu.img" \
  -initdata "$SCRIPT_DIR/userdata.img" \
  -data "$RUN/userdata-qemu.img" \
  -cache "$RUN/cache.img" \
  -wipe-data \
  -no-snapshot \
  -gpu "$GPU_MODE" \
  -memory "$MEMORY_MB"
LAUNCH_LINUX
fi

chmod +x "$LAUNCH_SCRIPT"
info "Launch script: $LAUNCH_SCRIPT"

# ============================================================
# Done
# ============================================================
echo
echo "============================================"
echo "  Build complete!"
echo "============================================"
echo
echo "Emulator images: $EXPORT_DIR"
echo "Launch script:   $LAUNCH_SCRIPT"
echo
echo "To start the emulator:"
echo "  $LAUNCH_SCRIPT"
echo
echo "To deploy OpenCyvis (already in system image):"
echo "  The app is pre-installed. Just launch the emulator."
echo
echo "To update the app after code changes:"
echo "  cd $ANDROID_DIR && ./deploy-emu.sh"
