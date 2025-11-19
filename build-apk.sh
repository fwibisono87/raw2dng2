#!/bin/bash

# Build script for Raw2DNG Android APK
# This script builds both debug and release APKs

set -e  # Exit on error

echo "========================================="
echo "Raw2DNG Android APK Build Script"
echo "========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if we're in the right directory
if [ ! -f "settings.gradle" ]; then
    echo -e "${RED}Error: settings.gradle not found!${NC}"
    echo "Please run this script from the project root directory."
    exit 1
fi

# Check if Adobe DNG SDK is present
DNG_SDK_DIR="app/src/main/cpp/dng_sdk"
DNG_SDK_FILES=$(find "$DNG_SDK_DIR" -name "dng_*.cpp" 2>/dev/null | wc -l)

echo "Checking Adobe DNG SDK..."

# Apply patches if needed
if [ -f "patch-dng-sdk.sh" ] && [ "$DNG_SDK_FILES" -gt 0 ]; then
    echo "Applying DNG SDK patches..."
    ./patch-dng-sdk.sh || echo -e "${YELLOW}Warning: Patch script failed, continuing anyway${NC}"
    echo ""
fi

if [ "$DNG_SDK_FILES" -eq 0 ]; then
    echo -e "${YELLOW}WARNING: Adobe DNG SDK not found!${NC}"
    echo "The app will build but only create placeholder DNG files."
    echo ""
    echo "To add the Adobe DNG SDK:"
    echo "  1. Download from: https://helpx.adobe.com/camera-raw/digital-negative.html"
    echo "  2. Extract the SDK"
    echo "  3. Copy files: cp /path/to/dng_sdk/source/*.{cpp,h} $DNG_SDK_DIR/"
    echo "  4. Rebuild the APK"
    echo ""
    read -p "Continue without DNG SDK? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Build cancelled."
        exit 1
    fi
else
    echo -e "${GREEN}✓ Found $DNG_SDK_FILES DNG SDK source files${NC}"
fi

echo ""
echo "Starting build..."
echo ""

# Clean previous builds (optional, comment out for faster incremental builds)
echo "Cleaning previous builds..."
./gradlew clean || true

# Build debug APK
echo ""
echo "Building debug APK..."
./gradlew assembleDebug

# Build release APK (unsigned)
echo ""
echo "Building release APK..."
./gradlew assembleRelease || echo -e "${YELLOW}Note: Release APK build skipped or failed (signing required)${NC}"

# Create output directory
mkdir -p output

# Copy APKs to output directory
echo ""
echo "Copying APKs to output directory..."

if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    cp app/build/outputs/apk/debug/app-debug.apk output/
    echo -e "${GREEN}✓ Debug APK: output/app-debug.apk${NC}"

    # Get APK info
    DEBUG_SIZE=$(du -h output/app-debug.apk | cut -f1)
    echo "  Size: $DEBUG_SIZE"
fi

if [ -f "app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
    cp app/build/outputs/apk/release/app-release-unsigned.apk output/
    echo -e "${GREEN}✓ Release APK: output/app-release-unsigned.apk${NC}"

    # Get APK info
    RELEASE_SIZE=$(du -h output/app-release-unsigned.apk | cut -f1)
    echo "  Size: $RELEASE_SIZE"
fi

echo ""
echo "========================================="
echo -e "${GREEN}Build Complete!${NC}"
echo "========================================="
echo ""
echo "APK files are in the 'output/' directory:"
echo ""
ls -lh output/*.apk 2>/dev/null || echo "No APK files found in output/"
echo ""
echo "To install on a device:"
echo "  adb install output/app-debug.apk"
echo ""
echo "Or transfer the APK to your Android device and install manually."
echo ""
