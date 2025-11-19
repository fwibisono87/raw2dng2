# Quick Setup Guide for Raw2DNG

This is a streamlined setup guide to get you started quickly.

## Prerequisites Checklist

- [ ] Android Studio installed
- [ ] Android SDK (API 24+) installed
- [ ] Android NDK installed (via SDK Manager)
- [ ] CMake installed (via SDK Manager)
- [ ] Adobe DNG SDK downloaded

## 5-Minute Setup

### Step 1: Install Development Tools (if not already installed)

1. Download and install [Android Studio](https://developer.android.com/studio)
2. Open Android Studio → Tools → SDK Manager
3. In "SDK Tools" tab, check:
   - ✅ NDK (Side by side)
   - ✅ CMake
   - ✅ Android SDK Build-Tools
4. Click "Apply" to install

### Step 2: Download Adobe DNG SDK

1. Visit: https://helpx.adobe.com/camera-raw/digital-negative.html
2. Download the Adobe DNG SDK (latest version)
3. Extract the ZIP file
4. Note the location of the `dng_sdk/source/` directory

### Step 3: Setup DNG SDK in Project

**Option A: Manual Copy**
```bash
# Copy all SDK files to project
cp /path/to/dng_sdk/source/*.cpp app/src/main/cpp/dng_sdk/
cp /path/to/dng_sdk/source/*.h app/src/main/cpp/dng_sdk/
```

**Option B: Using Helper Script**
```bash
# Place downloaded dng_sdk.zip in project root
mv ~/Downloads/dng_sdk*.zip ./dng_sdk.zip

# Run extraction script
./download_dng_sdk.sh --extract
```

### Step 4: Open and Build Project

1. Open Android Studio
2. File → Open → Select `raw2dng2` folder
3. Wait for Gradle sync (may take a few minutes first time)
4. Build → Make Project (or Ctrl+F9)

### Step 5: Run on Device

1. Connect Android device via USB (or start emulator)
2. Enable USB debugging on device:
   - Settings → About Phone → Tap "Build Number" 7 times
   - Settings → Developer Options → Enable "USB Debugging"
3. Click Run button (green triangle) in Android Studio
4. Select your device
5. Wait for app to install and launch

## Verify Installation

### Check DNG SDK Installation

```bash
# From project root, verify SDK files are present
ls -l app/src/main/cpp/dng_sdk/dng_*.cpp | wc -l
# Should show 50+ files
```

### Test Build

```bash
# Build from command line
./gradlew assembleDebug

# If successful, you'll see:
# BUILD SUCCESSFUL in XXs
```

### Test on Device

1. Launch the app
2. Check the log area at bottom of screen
3. Look for: "SDK Status: Adobe DNG SDK (version info)"
4. If you see warnings about SDK not available, review Step 3

## Quick Test

### Get Sample RAW Files

If you don't have RAW files on your device:

1. Download sample RAW files:
   - Canon CR2: https://raw.pixls.us/
   - Or use your camera's RAW files

2. Transfer to device:
   ```bash
   adb push sample.CR2 /sdcard/Download/
   ```

### Run Conversion

1. Open Raw2DNG app
2. Tap "SELECT RAW FILES"
3. Navigate to your RAW file
4. Select it
5. Tap "CONVERT TO DNG"
6. Watch the log for progress

### Check Output

```bash
# Pull converted DNG file
adb pull /sdcard/Android/data/com.raw2dng/files/Documents/Raw2DNG/sample.dng

# Or use device file manager to browse:
# /Android/data/com.raw2dng/files/Documents/Raw2DNG/
```

## Troubleshooting Quick Fixes

### "DNG SDK files not found" during build

```bash
# Verify SDK files are in correct location
ls app/src/main/cpp/dng_sdk/dng_*.cpp

# If empty, repeat Step 3
```

### "NDK not found"

1. Android Studio → Tools → SDK Manager
2. SDK Tools tab → Check "NDK (Side by side)"
3. Click Apply
4. Restart Android Studio

### "CMake not found"

1. Android Studio → Tools → SDK Manager
2. SDK Tools tab → Check "CMake"
3. Click Apply
4. Build → Clean Project
5. Build → Rebuild Project

### Build takes very long / hangs

```bash
# Increase Gradle memory in gradle.properties
echo "org.gradle.jvmargs=-Xmx4096m" >> gradle.properties

# Clean and rebuild
./gradlew clean build
```

### App crashes on startup

```bash
# Check logs
adb logcat | grep -i raw2dng

# Common cause: Missing libraries
# Solution: Rebuild and reinstall
./gradlew clean installDebug
```

## Next Steps

- Read the full [README.md](README.md) for detailed information
- Check [Troubleshooting](README.md#troubleshooting) section for common issues
- Test with your own RAW files
- Review logs for conversion success/failure

## Getting Help

1. Check `README.md` Troubleshooting section
2. Review Android Studio build output
3. Check `adb logcat` for runtime errors
4. Verify all prerequisites are installed

## Build Times (Reference)

- **First build**: 5-10 minutes (downloading dependencies, compiling DNG SDK)
- **Incremental builds**: 30-60 seconds (only changed files)
- **Clean build**: 2-5 minutes (recompiling everything)

The DNG SDK compilation is the slowest part. Be patient on first build!
