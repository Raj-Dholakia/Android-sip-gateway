#!/bin/bash
#
# Build tinymix for arm64-v8a using Android NDK
#
set -e

TINYALSA_SRC="/home/user/dev/modem/lineage-17.1/external/tinyalsa"
NDK_ROOT="$ANDROID_NDK_ROOT"
OUTPUT_DIR="$(pwd)/app/src/main/assets"

# Auto-detect NDK if not set
if [ -z "$NDK_ROOT" ]; then
    # Try common locations
    if [ -d "$HOME/Android/Sdk/ndk" ]; then
        NDK_ROOT=$(ls -d $HOME/Android/Sdk/ndk/* | sort -V | tail -1)
    elif [ -d "/opt/android-ndk" ]; then
        NDK_ROOT="/opt/android-ndk"
    fi
fi

if [ -z "$NDK_ROOT" ] || [ ! -d "$NDK_ROOT" ]; then
    echo "ERROR: Android NDK not found. Set ANDROID_NDK_ROOT or install NDK"
    exit 1
fi

echo "Using NDK: $NDK_ROOT"
echo "Tinyalsa source: $TINYALSA_SRC"
echo "Output: $OUTPUT_DIR"

# NDK toolchain
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/aarch64-linux-android21-clang"
AR="$TOOLCHAIN/bin/llvm-ar"

if [ ! -f "$CC" ]; then
    echo "ERROR: Compiler not found: $CC"
    exit 1
fi

# Build directory
BUILD_DIR="/tmp/tinymix_arm64_build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

echo ""
echo "=== Compiling tinyalsa library ==="
cd "$TINYALSA_SRC"

# Compile library sources
$CC -c -O2 -fPIC \
    -I include \
    -o "$BUILD_DIR/mixer.o" mixer.c

$CC -c -O2 -fPIC \
    -I include \
    -o "$BUILD_DIR/pcm.o" pcm.c

# Create static library
$AR rcs "$BUILD_DIR/libtinyalsa.a" \
    "$BUILD_DIR/mixer.o" \
    "$BUILD_DIR/pcm.o"

echo ""
echo "=== Compiling tinymix ==="
$CC -O2 -s \
    -I include \
    -o "$BUILD_DIR/tinymix-arm64" \
    tinymix.c \
    "$BUILD_DIR/libtinyalsa.a"

# Verify
if [ ! -f "$BUILD_DIR/tinymix-arm64" ]; then
    echo "ERROR: Build failed"
    exit 1
fi

file "$BUILD_DIR/tinymix-arm64"

# Copy to assets
echo ""
echo "=== Installing to assets ==="
mkdir -p "$OUTPUT_DIR"
cp "$BUILD_DIR/tinymix-arm64" "$OUTPUT_DIR/"
chmod +x "$OUTPUT_DIR/tinymix-arm64"

echo ""
echo "=== Build complete ==="
echo "Output: $OUTPUT_DIR/tinymix-arm64"
ls -lh "$OUTPUT_DIR"/tinymix*

echo ""
echo "Next steps:"
echo "  1. Update MainActivity.java to use tinymix-arm64 on Android 11+"
echo "  2. Keep tinymix (32-bit) for Android 8.1"
echo "  3. Rebuild and install APK"
