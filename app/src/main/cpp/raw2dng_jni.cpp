#include <jni.h>
#include <string>
#include <android/log.h>
#include "dng_converter.h"

#define LOG_TAG "Raw2DNG_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_raw2dng_DNGConverter_convertToDNG(
        JNIEnv* env,
        jobject /* this */,
        jstring inputPath,
        jstring outputPath) {

    const char* inputPathStr = env->GetStringUTFChars(inputPath, nullptr);
    const char* outputPathStr = env->GetStringUTFChars(outputPath, nullptr);

    LOGD("JNI: Converting %s to %s", inputPathStr, outputPathStr);

    std::string errorMessage;
    bool success = DNGConverter::convertToDNG(
        std::string(inputPathStr),
        std::string(outputPathStr),
        errorMessage
    );

    env->ReleaseStringUTFChars(inputPath, inputPathStr);
    env->ReleaseStringUTFChars(outputPath, outputPathStr);

    if (success) {
        return env->NewStringUTF("");  // Empty string indicates success
    } else {
        return env->NewStringUTF(errorMessage.c_str());
    }
}

JNIEXPORT jboolean JNICALL
Java_com_raw2dng_DNGConverter_isSDKAvailable(
        JNIEnv* env,
        jobject /* this */) {
#ifdef HAS_DNG_SDK
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_raw2dng_DNGConverter_getSDKVersion(
        JNIEnv* env,
        jobject /* this */) {
#ifdef HAS_DNG_SDK
    return env->NewStringUTF("Adobe DNG SDK (version info from dng_sdk)");
#else
    return env->NewStringUTF("SDK Not Available - Please install Adobe DNG SDK");
#endif
}

} // extern "C"
