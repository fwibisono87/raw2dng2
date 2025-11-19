# Raw2DNG - Android RAW to DNG Converter

A proof-of-concept Android application that converts proprietary RAW files (CR2, ARW, NEF, etc.) to Adobe DNG format using the Adobe DNG SDK.

## Features

- ✅ Select multiple RAW files from device storage
- ✅ Queue-based conversion processing
- ✅ Progress tracking with detailed logs
- ✅ Support for various RAW formats (CR2, ARW, NEF, RAF, ORF, etc.)
- ✅ Native C++ implementation using Adobe DNG SDK
- ✅ Simple, functional UI for proof-of-concept testing

## Prerequisites

### Development Environment

1. **Android Studio** (Arctic Fox or newer)
   - Download from: https://developer.android.com/studio

2. **Android NDK** (r21 or newer)
   - Install via Android Studio SDK Manager
   - Go to: Tools → SDK Manager → SDK Tools → NDK (Side by side)

3. **CMake** (3.22.1 or newer)
   - Install via Android Studio SDK Manager
   - Go to: Tools → SDK Manager → SDK Tools → CMake

### Adobe DNG SDK

**IMPORTANT**: You must download and install the Adobe DNG SDK before building this project.

#### Download Instructions:

1. Visit Adobe's DNG SDK page:
   - https://helpx.adobe.com/camera-raw/digital-negative.html
   - Or search for "Adobe DNG SDK download"

2. Download the latest version (1.6 or newer recommended)

3. Extract the downloaded ZIP file

4. Copy SDK files to the project:
   ```bash
   # Navigate to the extracted SDK directory
   cd /path/to/dng_sdk_x_x/

   # Copy all source files to the project
   cp dng_sdk/source/*.cpp /path/to/raw2dng2/app/src/main/cpp/dng_sdk/
   cp dng_sdk/source/*.h /path/to/raw2dng2/app/src/main/cpp/dng_sdk/
   ```

   Or use the provided helper script:
   ```bash
   # From the project root directory
   ./download_dng_sdk.sh
   # Follow the instructions, download the SDK, then:
   ./download_dng_sdk.sh --extract
   ```

5. Verify installation:
   ```bash
   ls app/src/main/cpp/dng_sdk/dng_*.cpp
   # Should show multiple DNG SDK source files
   ```

## Building the Project

### Option 1: Android Studio (Recommended)

1. Open Android Studio
2. Select "Open an Existing Project"
3. Navigate to the `raw2dng2` directory
4. Wait for Gradle sync to complete
5. Ensure the Adobe DNG SDK is installed (see above)
6. Click "Build" → "Make Project" or press `Ctrl+F9` (Windows/Linux) or `Cmd+F9` (Mac)
7. Connect an Android device or start an emulator
8. Click "Run" → "Run 'app'" or press `Shift+F10`

### Option 2: Command Line

```bash
# From project root directory
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Or build and install in one command
./gradlew build installDebug
```

### Build Output

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk` (after `./gradlew assembleRelease`)

## Installation

### Install on Device

1. Enable "Developer Options" on your Android device:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   - Go back to Settings → Developer Options
   - Enable "USB Debugging"

2. Connect device via USB

3. Install the app:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

   Or use Android Studio's "Run" button.

## Usage

### Testing the Conversion

1. **Launch the App**
   - Open "Raw2DNG" from your device's app drawer

2. **Check SDK Status**
   - The log at the bottom will show SDK availability status
   - If SDK is available: "SDK Status: Adobe DNG SDK (version info)"
   - If SDK is missing: Warning messages will appear

3. **Select RAW Files**
   - Tap "SELECT RAW FILES" button
   - Grant storage permissions if prompted
   - Navigate to your RAW files (CR2, ARW, NEF, etc.)
   - Select one or multiple files
   - The app will show "X file(s) selected"

4. **Convert Files**
   - Tap "CONVERT TO DNG" button
   - Monitor progress in the progress bar and log
   - Converted files are saved to: `/storage/emulated/0/Android/data/com.raw2dng/files/Documents/Raw2DNG/`

5. **Check Results**
   - Success: ✓ filename.CR2 -> filename.dng
   - Failure: ✗ filename.CR2: [error message]
   - Final summary shows successful and failed conversions

### Accessing Converted Files

Converted DNG files are saved to the app's external files directory:

```
/storage/emulated/0/Android/data/com.raw2dng/files/Documents/Raw2DNG/
```

You can access these files using:
- Android File Manager app
- ADB: `adb pull /storage/emulated/0/Android/data/com.raw2dng/files/Documents/Raw2DNG/`
- USB file transfer (MTP mode)

## Supported RAW Formats

The Adobe DNG SDK supports reading many proprietary RAW formats, including:

- **Canon**: CR2, CR3, CRW
- **Nikon**: NEF, NRW
- **Sony**: ARW, SRF, SR2
- **Fujifilm**: RAF
- **Olympus**: ORF
- **Panasonic**: RW2, RAW
- **Pentax**: PEF, DNG
- **Adobe**: DNG
- **And many more...**

**Note**: Format support depends on the Adobe DNG SDK version. Some newer proprietary formats may require the latest SDK version.

## Troubleshooting

### Build Errors

**"Adobe DNG SDK source files not found"**
- Solution: Download and install the Adobe DNG SDK as described above
- The app will still compile but create placeholder files instead of real conversions

**"NDK not configured"**
- Solution: Install Android NDK via Android Studio SDK Manager
- Go to: Tools → SDK Manager → SDK Tools → Check "NDK (Side by side)"

**"CMake not found"**
- Solution: Install CMake via Android Studio SDK Manager
- Go to: Tools → SDK Manager → SDK Tools → Check "CMake"

### Runtime Errors

**"Storage permission denied"**
- Solution: Grant storage permissions when prompted
- Or manually: Settings → Apps → Raw2DNG → Permissions → Storage → Allow

**"Conversion failed" with SDK warnings**
- Solution: Ensure Adobe DNG SDK is properly installed
- Check that .cpp and .h files are in `app/src/main/cpp/dng_sdk/`
- Rebuild the project: Build → Clean Project, then Build → Rebuild Project

**"File not found" or "Cannot read file"**
- Solution: Ensure RAW files are accessible on device storage
- Try copying RAW files to a public directory like Downloads or DCIM

### Testing Without DNG SDK

The app can be built and run without the Adobe DNG SDK for UI testing purposes:
- It will create placeholder DNG files instead of real conversions
- Useful for testing the queue mechanism and UI flow
- Real conversion requires the SDK to be installed

## Project Structure

```
raw2dng2/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/                    # Native C++ code
│   │   │   │   ├── dng_sdk/           # Adobe DNG SDK files (user-provided)
│   │   │   │   ├── CMakeLists.txt     # CMake build configuration
│   │   │   │   ├── dng_converter.cpp  # DNG conversion logic
│   │   │   │   ├── raw2dng_jni.cpp    # JNI bindings
│   │   │   │   └── xmp_stub.cpp       # XMP SDK stub
│   │   │   ├── java/com/raw2dng/      # Kotlin/Java code
│   │   │   │   ├── MainActivity.kt    # Main UI activity
│   │   │   │   ├── DNGConverter.kt    # JNI wrapper
│   │   │   │   └── ConversionQueue.kt # Queue manager
│   │   │   ├── res/                   # Android resources
│   │   │   └── AndroidManifest.xml    # App manifest
│   │   └── build.gradle               # App-level build config
│   └── build.gradle                   # Module-level build config
├── build.gradle                       # Project-level build config
├── settings.gradle                    # Project settings
├── download_dng_sdk.sh               # Helper script for SDK setup
└── README.md                         # This file
```

## Technical Details

### Architecture

- **UI Layer**: Kotlin with Android SDK
  - File picker using Storage Access Framework
  - Coroutines for asynchronous operations
  - LiveData for UI updates

- **Business Logic**: Kotlin
  - Queue-based task processing
  - Error handling and logging
  - File I/O operations

- **Native Layer**: C++ with JNI
  - Adobe DNG SDK integration
  - RAW file parsing and DNG writing
  - Exception handling and error reporting

### Build Configuration

- **Min SDK**: Android 7.0 (API 24)
- **Target SDK**: Android 14 (API 34)
- **NDK**: C++17 with STL support
- **ABIs**: arm64-v8a, armeabi-v7a, x86, x86_64

### Dependencies

- AndroidX Core KTX
- AndroidX AppCompat
- Material Design Components
- Kotlin Coroutines
- Adobe DNG SDK (external, user-provided)

## Known Limitations

This is a **proof-of-concept** application with the following limitations:

1. **UI/UX**: Minimal, functional interface - not production-ready
2. **Error Handling**: Basic error messages - could be more user-friendly
3. **File Management**: Limited file browsing capabilities
4. **Performance**: Sequential processing - could be parallelized
5. **Format Detection**: No automatic format validation
6. **Metadata**: XMP support disabled (qDNGUseXMP=0)
7. **Testing**: Limited testing on various RAW formats
8. **Icon**: Placeholder icon - needs proper app icon

## Future Improvements

Potential enhancements for production version:

- [ ] Parallel conversion processing
- [ ] Better error handling and user feedback
- [ ] RAW format validation before conversion
- [ ] Conversion settings/options (compression, preview size, etc.)
- [ ] Progress notifications for background processing
- [ ] File browser with RAW file filtering
- [ ] Batch operations (delete, share, etc.)
- [ ] XMP metadata preservation
- [ ] Custom output directory selection
- [ ] App icon and branding
- [ ] Comprehensive testing suite

## License

This project structure and application code is provided as-is for educational purposes.

**Important**: The Adobe DNG SDK has its own license agreement. You must comply with Adobe's licensing terms when using the DNG SDK. Download and review the license from Adobe's website.

## Resources

- [Adobe DNG SDK](https://helpx.adobe.com/camera-raw/digital-negative.html)
- [Adobe DNG Specification](https://helpx.adobe.com/photoshop/digital-negative.html)
- [Android NDK Documentation](https://developer.android.com/ndk)
- [JNI Guide](https://developer.android.com/training/articles/perf-jni)

## Support

This is a proof-of-concept project. For issues or questions:
1. Check the Troubleshooting section above
2. Review Adobe DNG SDK documentation
3. Check Android NDK documentation for native build issues

## Acknowledgments

- Adobe for the DNG SDK
- Android Open Source Project
- JetBrains for Kotlin
