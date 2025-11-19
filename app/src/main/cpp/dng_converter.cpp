#include "dng_converter.h"
#include <android/log.h>
#include <fstream>
#include <cstring>

#define LOG_TAG "DNGConverter"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Include DNG SDK headers if available
// These will only work if the Adobe DNG SDK has been downloaded and placed in dng_sdk/
#ifdef __has_include
#  if __has_include("dng_host.h")
#    define HAS_DNG_SDK 1
#    include "dng_exceptions.h"
#    include "dng_file_stream.h"
#    include "dng_globals.h"
#    include "dng_host.h"
#    include "dng_ifd.h"
#    include "dng_image_writer.h"
#    include "dng_info.h"
#    include "dng_negative.h"
#    include "dng_simple_image.h"
#    include "dng_stream.h"
#    include "dng_tag_codes.h"
#    include "dng_tag_types.h"
#    include "dng_tag_values.h"
#  endif
#endif

bool DNGConverter::isDNGSDKAvailable() {
#ifdef HAS_DNG_SDK
    return true;
#else
    return false;
#endif
}

bool DNGConverter::convertToDNG(const std::string& inputPath, const std::string& outputPath, std::string& errorMessage) {
    LOGD("Converting %s to %s", inputPath.c_str(), outputPath.c_str());

#ifdef HAS_DNG_SDK
    try {
        // Initialize DNG SDK
        dng_xmp_sdk::InitializeSDK();

        // Create a DNG host
        dng_host host;

        // Open the input RAW file
        dng_file_stream stream(inputPath.c_str());

        // Read the file information
        dng_info info;
        info.Parse(host, stream);
        info.PostParse(host);

        if (!info.IsValidDNG()) {
            LOGD("File is not a valid DNG, attempting to read as proprietary RAW");
        }

        // Create the negative
        AutoPtr<dng_negative> negative;
        negative.Reset(host.Make_dng_negative());

        // Parse the negative
        negative->Parse(host, stream, info);
        negative->PostParse(host, stream, info);

        // Synchronize metadata
        negative->SynchronizeMetadata();

        // Create output stream
        dng_file_stream outputStream(outputPath.c_str(), true);

        // Write the DNG file
        dng_image_writer writer;
        writer.WriteDNG(host, outputStream, *negative.Get());

        // Clean up
        dng_xmp_sdk::TerminateSDK();

        LOGD("Conversion successful");
        return true;

    } catch (const dng_exception& e) {
        LOGE("DNG SDK exception: %d", e.ErrorCode());
        errorMessage = "DNG SDK error code: " + std::to_string(e.ErrorCode());
        return false;
    } catch (const std::exception& e) {
        LOGE("Standard exception: %s", e.what());
        errorMessage = std::string("Error: ") + e.what();
        return false;
    } catch (...) {
        LOGE("Unknown exception occurred");
        errorMessage = "Unknown error occurred during conversion";
        return false;
    }
#else
    // Adobe DNG SDK not available - provide helpful error message
    errorMessage = "Adobe DNG SDK not available. Please download and install the SDK as per README instructions.";
    LOGE("%s", errorMessage.c_str());

    // For testing purposes, create a dummy output file
    std::ofstream dummyFile(outputPath);
    if (dummyFile.is_open()) {
        dummyFile << "This is a placeholder DNG file.\n";
        dummyFile << "The Adobe DNG SDK is required for actual RAW to DNG conversion.\n";
        dummyFile << "Please see app/src/main/cpp/dng_sdk/README.md for setup instructions.\n";
        dummyFile.close();
        LOGD("Created placeholder file for testing");
    }

    return false;
#endif
}
