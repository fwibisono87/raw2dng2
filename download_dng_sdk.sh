#!/bin/bash

# Script to download and setup Adobe DNG SDK
# This is a helper script - you may need to adjust based on the actual SDK version

set -e

DNG_SDK_DIR="app/src/main/cpp/dng_sdk"
TEMP_DIR="/tmp/dng_sdk_download"

echo "========================================="
echo "Adobe DNG SDK Download Helper"
echo "========================================="
echo ""
echo "NOTE: This script requires manual download of the Adobe DNG SDK"
echo ""
echo "Please follow these steps:"
echo ""
echo "1. Visit: https://helpx.adobe.com/camera-raw/digital-negative.html"
echo "2. Download the latest Adobe DNG SDK (usually a .zip file)"
echo "3. Save it to this directory as 'dng_sdk.zip'"
echo ""
echo "Once downloaded, run this script again with the --extract flag:"
echo "  ./download_dng_sdk.sh --extract"
echo ""

if [ "$1" == "--extract" ]; then
    if [ ! -f "dng_sdk.zip" ]; then
        echo "ERROR: dng_sdk.zip not found in current directory"
        echo "Please download it first from Adobe's website"
        exit 1
    fi

    echo "Extracting SDK..."
    mkdir -p "$TEMP_DIR"
    unzip -q dng_sdk.zip -d "$TEMP_DIR"

    # Find the source directory (structure may vary by version)
    SOURCE_DIR=$(find "$TEMP_DIR" -type d -name "source" | head -n 1)

    if [ -z "$SOURCE_DIR" ]; then
        echo "ERROR: Could not find 'source' directory in extracted SDK"
        echo "Please manually copy the SDK files to $DNG_SDK_DIR"
        exit 1
    fi

    echo "Copying SDK files to $DNG_SDK_DIR..."
    cp "$SOURCE_DIR"/*.cpp "$DNG_SDK_DIR/" 2>/dev/null || true
    cp "$SOURCE_DIR"/*.h "$DNG_SDK_DIR/" 2>/dev/null || true

    # Clean up
    rm -rf "$TEMP_DIR"

    echo ""
    echo "✓ SDK extracted successfully!"
    echo "✓ Files copied to $DNG_SDK_DIR"
    echo ""
    echo "You can now build the Android project."
else
    echo "Waiting for manual download..."
fi
