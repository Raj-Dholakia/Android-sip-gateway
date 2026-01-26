#!/bin/bash
#===============================================================================
# Production Release Build Script for GSM-SIP Gateway
#===============================================================================
# This script builds a signed release APK ready for distribution.
#
# Prerequisites:
#   1. Run ./setup-keystore.sh first to create signing keystore
#   2. Ensure keystore.properties exists with signing credentials
#
# Usage:
#   ./build-release.sh [options]
#
# Options:
#   --clean         Clean build (removes build/ directory)
#   --bump-version  Increment versionCode automatically
#   --version NAME  Set custom versionName (e.g., "1.0.1")
#===============================================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Paths
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_GRADLE="$PROJECT_DIR/app/build.gradle"
KEYSTORE_PROPS="$PROJECT_DIR/keystore.properties"
OUTPUT_DIR="$PROJECT_DIR/release-output"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"

# Parse command line arguments
CLEAN_BUILD=false
BUMP_VERSION=false
CUSTOM_VERSION=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --clean)
            CLEAN_BUILD=true
            shift
            ;;
        --bump-version)
            BUMP_VERSION=true
            shift
            ;;
        --version)
            CUSTOM_VERSION="$2"
            shift 2
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

#===============================================================================
# Functions
#===============================================================================

print_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}\n"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

check_prerequisites() {
    print_header "Checking Prerequisites"

    # Check if keystore.properties exists
    if [ ! -f "$KEYSTORE_PROPS" ]; then
        print_error "keystore.properties not found!"
        echo ""
        echo "Run ./setup-keystore.sh first to create signing keystore"
        echo "Or create keystore.properties manually with:"
        echo ""
        echo "  storeFile=/path/to/release-keystore.jks"
        echo "  storePassword=your_store_password"
        echo "  keyAlias=gateway-release-key"
        echo "  keyPassword=your_key_password"
        echo ""
        exit 1
    fi
    print_success "keystore.properties found"

    # Check if keystore file exists
    KEYSTORE_FILE=$(grep "storeFile=" "$KEYSTORE_PROPS" | cut -d'=' -f2)
    if [ ! -f "$KEYSTORE_FILE" ]; then
        print_error "Keystore file not found: $KEYSTORE_FILE"
        echo "Run ./setup-keystore.sh to create it"
        exit 1
    fi
    print_success "Keystore file exists: $KEYSTORE_FILE"

    # Check Gradle wrapper
    if [ ! -f "$PROJECT_DIR/gradlew" ]; then
        print_error "gradlew not found!"
        exit 1
    fi
    print_success "Gradle wrapper found"

    # Make gradlew executable
    chmod +x "$PROJECT_DIR/gradlew"
}

get_current_version() {
    VERSION_CODE=$(grep "versionCode" "$BUILD_GRADLE" | head -1 | sed 's/[^0-9]*//g')
    VERSION_NAME=$(grep "versionName" "$BUILD_GRADLE" | head -1 | sed 's/.*"\(.*\)".*/\1/')
    echo -e "Current version: ${YELLOW}$VERSION_NAME${NC} (code: $VERSION_CODE)"
}

bump_version_code() {
    print_header "Bumping Version"

    get_current_version

    NEW_VERSION_CODE=$((VERSION_CODE + 1))

    # Update versionCode in build.gradle
    sed -i "s/versionCode $VERSION_CODE/versionCode $NEW_VERSION_CODE/" "$BUILD_GRADLE"

    # Update versionName if custom version provided
    if [ -n "$CUSTOM_VERSION" ]; then
        sed -i "s/versionName \"$VERSION_NAME\"/versionName \"$CUSTOM_VERSION\"/" "$BUILD_GRADLE"
        VERSION_NAME="$CUSTOM_VERSION"
    fi

    print_success "Version bumped to: $VERSION_NAME (code: $NEW_VERSION_CODE)"
}

clean_build() {
    print_header "Cleaning Build"

    cd "$PROJECT_DIR"
    ./gradlew clean

    print_success "Build cleaned"
}

build_release() {
    print_header "Building Release APK"

    cd "$PROJECT_DIR"

    # Build release APK
    ./gradlew assembleRelease

    if [ ! -f "$APK_PATH" ]; then
        print_error "APK build failed! File not found: $APK_PATH"
        exit 1
    fi

    print_success "Release APK built successfully"
}

copy_output() {
    print_header "Copying Output"

    # Create output directory
    mkdir -p "$OUTPUT_DIR"

    # Get version info
    get_current_version

    # Generate output filename
    TIMESTAMP=$(date +%Y%m%d_%H%M%S)
    OUTPUT_APK="$OUTPUT_DIR/gateway-${VERSION_NAME}-${VERSION_CODE}-${TIMESTAMP}.apk"

    # Copy APK
    cp "$APK_PATH" "$OUTPUT_APK"

    # Create "latest" symlink
    ln -sf "$(basename "$OUTPUT_APK")" "$OUTPUT_DIR/gateway-latest.apk"

    print_success "APK copied to: $OUTPUT_APK"

    # Show APK info
    APK_SIZE=$(du -h "$OUTPUT_APK" | cut -f1)
    print_success "APK size: $APK_SIZE"
}

show_summary() {
    print_header "Build Summary"

    get_current_version

    echo "Version:  $VERSION_NAME (code: $VERSION_CODE)"
    echo "APK:      $OUTPUT_APK"
    echo "Size:     $APK_SIZE"
    echo ""
    echo -e "${GREEN}✓ Production release build completed successfully!${NC}"
    echo ""
    echo "Next steps:"
    echo "  1. Test the APK: adb install -r $OUTPUT_APK"
    echo "  2. Verify signing: jarsigner -verify -verbose -certs $OUTPUT_APK"
    echo "  3. Distribute or upload to release platform"
}

#===============================================================================
# Main Execution
#===============================================================================

print_header "GSM-SIP Gateway - Production Release Build"

# Step 1: Check prerequisites
check_prerequisites

# Step 2: Show current version
get_current_version

# Step 3: Bump version if requested
if [ "$BUMP_VERSION" = true ]; then
    bump_version_code
fi

# Step 4: Clean build if requested
if [ "$CLEAN_BUILD" = true ]; then
    clean_build
fi

# Step 5: Build release APK
build_release

# Step 6: Copy to output directory
copy_output

# Step 7: Show summary
show_summary
