# Adobe DNG SDK Integration

## Download Instructions

1. Download the Adobe DNG SDK from Adobe's official website:
   - Visit: https://helpx.adobe.com/camera-raw/digital-negative.html
   - Or search for "Adobe DNG SDK download" to find the latest version

2. Extract the downloaded SDK

3. Copy the following directories from the SDK to this location:
   - `dng_sdk/source/*.h` -> Place all header files here
   - `dng_sdk/source/*.cpp` -> Place all source files here

4. The expected structure should be:
   ```
   app/src/main/cpp/dng_sdk/
   ├── dng_*.h (all DNG SDK headers)
   ├── dng_*.cpp (all DNG SDK source files)
   └── README.md (this file)
   ```

## SDK Version

This project is tested with Adobe DNG SDK 1.6 and later versions.

## License

The Adobe DNG SDK is licensed under the Adobe DNG SDK License Agreement.
Make sure you comply with Adobe's licensing terms.
