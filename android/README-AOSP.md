# OpenCyvis - AOSP Integration

OpenCyvis is a privileged system app. It can be built standalone with Gradle for development, or integrated into an AOSP system image for production.

## Gradle Build (Development)

```bash
cd android
./gradlew assembleDebug
```

To build, sign with platform key, and install to a connected emulator:

```bash
./scripts/install-dev.sh
```

## AOSP Integration

### 1. Symlink into the AOSP tree

```bash
ln -s /path/to/aiphone/android $AOSP_ROOT/packages/apps/OpenCyvis
```

### 2. Add to device makefile

In your device makefile (e.g., `device/<vendor>/<device>/device.mk`):

```makefile
$(call inherit-product, packages/apps/OpenCyvis/product.mk)
```

Or manually:

```makefile
PRODUCT_PACKAGES += OpenCyvis

PRODUCT_COPY_FILES += \
    packages/apps/OpenCyvis/privapp-permissions-opencyvis.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-opencyvis.xml
```

### 3. Build

```bash
source build/envsetup.sh
lunch <target>
make OpenCyvis
```

## Platform Key Signing

System apps must be signed with the platform key to get privileged permissions. Two options:

**Option A: Local key pair**

Place `platform.pk8` and `platform.x509.pem` in `android/platform-key/`.

**Option B: AOSP tree**

Set `AOSP_ROOT` environment variable. The script finds keys at `$AOSP_ROOT/build/target/product/security/`.

Then sign:

```bash
./scripts/sign-apk.sh app/build/outputs/apk/debug/app-debug.apk
```

## Framework Stubs

To compile against hidden Android APIs, extract framework-stubs.jar from an AOSP build:

```bash
./scripts/extract-framework-stubs.sh /path/to/aosp
```

This copies the jar to `app/libs/framework-stubs.jar`. Add it as a `compileOnly` dependency in `build.gradle.kts`:

```kotlin
dependencies {
    compileOnly(files("libs/framework-stubs.jar"))
}
```

## Required Permissions

OpenCyvis requires the following privileged permissions (defined in `privapp-permissions-opencyvis.xml`):

| Permission | Purpose |
|---|---|
| `INJECT_EVENTS` | Simulate touch and key events for UI automation |
| `READ_FRAME_BUFFER` | Capture screen content for vision analysis |
| `CAPTURE_SECURE_VIDEO_OUTPUT` | Access secure display content |
| `INTERNAL_SYSTEM_WINDOW` | Display overlays as a system app |

These permissions are only grantable to apps in the `/system/priv-app/` partition, which is why OpenCyvis must be built as a privileged system app.
