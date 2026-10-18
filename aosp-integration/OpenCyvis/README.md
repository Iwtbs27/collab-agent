# OpenCyvis AOSP Integration

This directory contains everything needed to integrate OpenCyvis into any AOSP build as a prebuilt privileged system app.

## Quick Start

```bash
# 1. Download the prebuilt APK
./download_prebuilt.sh

# 2. Copy this directory into your AOSP tree
cp -r . $AOSP_ROOT/packages/apps/OpenCyvis/

# 3. Add to your device's product makefile
#    (e.g., device/<vendor>/<device>/device.mk)
```

Add the following to your device makefile:

```makefile
PRODUCT_PACKAGES += OpenCyvis

PRODUCT_COPY_FILES += \
    packages/apps/OpenCyvis/privapp-permissions-opencyvis.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-opencyvis.xml
```

Then build:

```bash
source build/envsetup.sh
lunch <your-target>
m
```

## How It Works

- `Android.bp` uses `android_app_import` to include the prebuilt APK
- The AOSP build system automatically re-signs the APK with your platform key (`certificate: "platform"`)
- The app is installed to `system_ext/priv-app/` as a privileged system app
- `privapp-permissions-opencyvis.xml` grants the required signature-level permissions

## Signing

You do **not** need to manage signing manually. The `certificate: "platform"` directive in `Android.bp` tells the AOSP build to strip the existing signature and re-sign with your build's platform key. This works regardless of which key was used to sign the prebuilt APK.

## Downloading a Specific Version

```bash
# Latest release
./download_prebuilt.sh

# Specific version
./download_prebuilt.sh v1.2.0
```

## Files

| File | Purpose |
|------|---------|
| `Android.bp` | AOSP build module definition (prebuilt import) |
| `OpenCyvis.apk` | Prebuilt APK (downloaded, not in git) |
| `privapp-permissions-opencyvis.xml` | Privileged permission allowlist |
| `download_prebuilt.sh` | Script to fetch APK from GitHub Releases |

## Required Permissions

| Permission | Purpose |
|---|---|
| `INJECT_EVENTS` | Touch/key injection for UI automation |
| `READ_FRAME_BUFFER` | Screen capture via SurfaceControl |
| `CAPTURE_SECURE_VIDEO_OUTPUT` | Capture DRM/secure content |
| `INTERNAL_SYSTEM_WINDOW` | System-level overlay windows |
| `MANAGE_ACTIVITY_TASKS` | VirtualDisplay task management |

## Device Compatibility

This integration works with any AOSP target: emulators (`sdk_phone64_arm64`), Pixel devices (`aosp_raven`), or custom hardware. The only requirement is that the build uses standard AOSP signing infrastructure.

## Updating

After initial installation, update the app directly without rebuilding the AOSP image:

```bash
./download_prebuilt.sh
adb install OpenCyvis.apk
```

Android allows system apps to be updated via the data partition — the newer version takes precedence over the one in the system image.
