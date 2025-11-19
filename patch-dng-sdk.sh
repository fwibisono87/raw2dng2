#!/bin/bash

# Patch script for Adobe DNG SDK filename bug
# This fixes a bug in dng_file_stream.cpp where variable names are incorrect

DNG_FILE="app/src/main/cpp/dng_sdk/dng_file_stream.cpp"

if [ ! -f "$DNG_FILE" ]; then
    echo "Error: $DNG_FILE not found"
    echo "Make sure Adobe DNG SDK is installed"
    exit 1
fi

echo "Patching dng_file_stream.cpp for Android compatibility..."

# Restore from backup if it exists (in case we need to re-patch)
if [ -f "$DNG_FILE.orig" ]; then
    cp "$DNG_FILE.orig" "$DNG_FILE"
else
    # Create original backup (only once)
    cp "$DNG_FILE" "$DNG_FILE.orig"
fi

# Apply patches for different line patterns
# The bug: Adobe uses 'filename' or 'name' but the actual parameter is different

# Pattern 1: Replace 'name.Get()' with 'fName.Get()' (most common)
sed -i 's/name\.Get());$/fName.Get());/g' "$DNG_FILE"

# Pattern 2: Replace standalone 'filename' with 'fName.Get()'
sed -i 's/filename);$/fName.Get());/g' "$DNG_FILE"

# Pattern 3: Replace standalone 'name' with 'fName.Get()'
sed -i 's/([^a-zA-Z_])name);$/\1fName.Get());/g' "$DNG_FILE"

echo "✓ Patched successfully!"
echo "Original saved to: $DNG_FILE.orig"
