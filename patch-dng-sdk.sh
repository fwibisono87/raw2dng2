#!/bin/bash

# Patch script for Adobe DNG SDK filename bug
# This fixes a bug in dng_file_stream.cpp where 'filename' is undefined

DNG_FILE="app/src/main/cpp/dng_sdk/dng_file_stream.cpp"

if [ ! -f "$DNG_FILE" ]; then
    echo "Error: $DNG_FILE not found"
    echo "Make sure Adobe DNG SDK is installed"
    exit 1
fi

echo "Patching dng_file_stream.cpp for Android compatibility..."

# Check if already patched
if grep -q "name.Get()" "$DNG_FILE"; then
    echo "File appears to already be patched. Skipping."
    exit 0
fi

# Create backup
cp "$DNG_FILE" "$DNG_FILE.backup"

# Apply patch using sed
# The bug is that 'filename' is used but should be 'name.Get()'
sed -i 's/filename);$/name.Get());/g' "$DNG_FILE"

echo "✓ Patched successfully!"
echo "Backup saved to: $DNG_FILE.backup"
