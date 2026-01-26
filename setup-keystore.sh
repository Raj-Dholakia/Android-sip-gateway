#!/bin/bash
#===============================================================================
# Keystore Setup Script for GSM-SIP Gateway
#===============================================================================
# This script creates a release signing keystore for the Android app.
#
# Usage:
#   ./setup-keystore.sh
#
# The script will:
#   1. Generate a new keystore file (release-keystore.jks)
#   2. Create keystore.properties with signing credentials
#   3. Update app/build.gradle to use the keystore
#===============================================================================

set -e  # Exit on error

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
KEYSTORE_FILE="$PROJECT_DIR/release-keystore.jks"
KEYSTORE_PROPS="$PROJECT_DIR/keystore.properties"
BUILD_GRADLE="$PROJECT_DIR/app/build.gradle"

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

print_header "GSM-SIP Gateway - Keystore Setup"

# Check if keystore already exists
if [ -f "$KEYSTORE_FILE" ]; then
    print_warning "Keystore already exists: $KEYSTORE_FILE"
    read -p "Overwrite existing keystore? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborted."
        exit 0
    fi
    rm -f "$KEYSTORE_FILE"
fi

# Collect keystore information
echo "Enter keystore information:"
echo ""

read -p "Key alias (default: gateway-release-key): " KEY_ALIAS
KEY_ALIAS=${KEY_ALIAS:-gateway-release-key}

read -sp "Keystore password: " STORE_PASSWORD
echo ""

read -sp "Key password (press Enter to use same as keystore): " KEY_PASSWORD
echo ""
KEY_PASSWORD=${KEY_PASSWORD:-$STORE_PASSWORD}

read -p "Your name (CN): " CN
read -p "Organization (O): " ORG
read -p "Organization Unit (OU, optional): " OU
read -p "City (L): " CITY
read -p "State (ST): " STATE
read -p "Country code (C, e.g., US, RU): " COUNTRY

# Build DN (Distinguished Name)
DN="CN=$CN, O=$ORG"
[ -n "$OU" ] && DN="$DN, OU=$OU"
DN="$DN, L=$CITY, ST=$STATE, C=$COUNTRY"

# Generate keystore
print_header "Generating Keystore"

keytool -genkeypair \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -keystore "$KEYSTORE_FILE" \
    -storepass "$STORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "$DN"

if [ ! -f "$KEYSTORE_FILE" ]; then
    print_error "Failed to create keystore!"
    exit 1
fi

print_success "Keystore created: $KEYSTORE_FILE"

# Create keystore.properties
print_header "Creating keystore.properties"

cat > "$KEYSTORE_PROPS" << EOF
# Keystore configuration for release builds
# DO NOT commit this file to version control!
storeFile=$KEYSTORE_FILE
storePassword=$STORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF

print_success "Created: $KEYSTORE_PROPS"

# Add keystore.properties to .gitignore
if [ -f "$PROJECT_DIR/.gitignore" ]; then
    if ! grep -q "keystore.properties" "$PROJECT_DIR/.gitignore"; then
        echo "" >> "$PROJECT_DIR/.gitignore"
        echo "# Release signing" >> "$PROJECT_DIR/.gitignore"
        echo "keystore.properties" >> "$PROJECT_DIR/.gitignore"
        echo "release-keystore.jks" >> "$PROJECT_DIR/.gitignore"
        print_success "Added keystore files to .gitignore"
    fi
fi

# Update app/build.gradle with signing configuration
print_header "Updating build.gradle"

# Check if signingConfigs already exists
if grep -q "signingConfigs" "$BUILD_GRADLE"; then
    print_warning "signingConfigs already exists in build.gradle"
    print_warning "Please manually verify the configuration"
else
    # Backup build.gradle
    cp "$BUILD_GRADLE" "$BUILD_GRADLE.backup"

    # Insert signingConfigs before android block
    awk '
    BEGIN { found=0 }
    /^android \{/ {
        if (!found) {
            print "// Load keystore properties"
            print "def keystorePropertiesFile = rootProject.file(\"keystore.properties\")"
            print "def keystoreProperties = new Properties()"
            print "if (keystorePropertiesFile.exists()) {"
            print "    keystoreProperties.load(new FileInputStream(keystorePropertiesFile))"
            print "}"
            print ""
            found=1
        }
        print
        next
    }
    /^    buildTypes \{/ {
        print "    signingConfigs {"
        print "        release {"
        print "            if (keystorePropertiesFile.exists()) {"
        print "                storeFile file(keystoreProperties[\"storeFile\"])"
        print "                storePassword keystoreProperties[\"storePassword\"]"
        print "                keyAlias keystoreProperties[\"keyAlias\"]"
        print "                keyPassword keystoreProperties[\"keyPassword\"]"
        print "            }"
        print "        }"
        print "    }"
        print ""
    }
    { print }
    ' "$BUILD_GRADLE" > "$BUILD_GRADLE.tmp"

    mv "$BUILD_GRADLE.tmp" "$BUILD_GRADLE"

    # Update release buildType to use signingConfig
    sed -i '/buildTypes {/,/release {/ {
        /release {/a\
            signingConfig signingConfigs.release
    }' "$BUILD_GRADLE"

    print_success "Updated build.gradle with signing configuration"
    print_success "Backup saved: $BUILD_GRADLE.backup"
fi

# Show summary
print_header "Setup Complete"

echo "Keystore information:"
echo "  File:      $KEYSTORE_FILE"
echo "  Alias:     $KEY_ALIAS"
echo "  Validity:  10000 days (~27 years)"
echo ""
echo -e "${GREEN}✓ Keystore setup completed successfully!${NC}"
echo ""
echo "IMPORTANT:"
echo "  1. Keep $KEYSTORE_FILE and keystore.properties SECURE"
echo "  2. DO NOT commit these files to version control"
echo "  3. Backup the keystore - you cannot publish updates without it!"
echo ""
echo "Next step:"
echo "  Run ./build-release.sh to build a signed release APK"
