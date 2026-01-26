# PJSIP Build for Android

Scripts and configuration for building PJSIP library for Android.

Based on [pjsip-android-builder](https://github.com/AoiMoe/pjsip-android-builder).

## Requirements

- Ubuntu 20.04+ or Docker
- ~10GB disk space

## Build

```bash
# First time - downloads NDK, SDK, PJSIP sources, builds everything
./prepare-build-system
./build

# Output: output/pjsip-build-output/
```

## Configuration

Edit `config.conf` to customize:

```bash
# PJSIP version
PJSIP_VERSION=2.14.1

# Target architectures
TARGET_ARCHS=("arm64-v8a")

# Android API level
TARGET_ANDROID_API=21

# Enable/disable features
ENABLE_OPENSSL=1
ENABLE_OPENH264=1
ENABLE_OPUS=1
ENABLE_BCG729=1
```

## Output

After successful build:
- `output/pjsip-build-output/lib/` - Native libraries (`.so`)
- `output/pjsip-build-output/java/` - Java bindings

Copy to project:
```bash
cp output/pjsip-build-output/lib/arm64-v8a/libpjsua2.so ../app/libs/arm64-v8a/
cp -r output/pjsip-build-output/java/org ../app/src/main/java/
```

## Patches

The `patches/` directory contains optional patches for PJSIP:
- `fixed_callid/` - Use fixed Call-ID
- `export_rtcp_fb/` - Export RTCP feedback data
- `fix_missing_contact_header_on_incall_ip_change/` - Fix Contact header issues

Enable patches in `config.conf`:
```bash
USE_FIXED_CALLID=1
EXPORT_RTCP_FB_DATA=1
FIX_MISSING_CONTACT_HEADER=1
```
