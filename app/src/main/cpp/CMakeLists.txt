cmake_minimum_required(VERSION 3.22.1)

project("raw2dng")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -frtti -fexceptions -Wno-deprecated-declarations")

# Include directories
include_directories(${CMAKE_SOURCE_DIR}/dng_sdk)
include_directories(${ANDROID_NDK}/sources/android/support/include)

# Collect all DNG SDK source files
file(GLOB DNG_SDK_SOURCES
    "${CMAKE_SOURCE_DIR}/dng_sdk/dng_*.cpp"
)

# If DNG SDK files are not present, create a dummy list to allow CMake configuration
if(NOT DNG_SDK_SOURCES)
    message(WARNING "Adobe DNG SDK source files not found in ${CMAKE_SOURCE_DIR}/dng_sdk/")
    message(WARNING "Please download and extract the Adobe DNG SDK before building.")
    message(WARNING "See app/src/main/cpp/dng_sdk/README.md for instructions.")
    # Create a minimal dummy source to allow project configuration
    set(DNG_SDK_SOURCES "")
endif()

# XMP SDK - using simplified stubs for now
# In production, you'd want to include the full XMP SDK from Adobe
add_library(xmp_stub STATIC
    xmp_stub.cpp
)

# DNG SDK library
if(DNG_SDK_SOURCES)
    add_library(dng_sdk STATIC
        ${DNG_SDK_SOURCES}
    )
    target_link_libraries(dng_sdk
        xmp_stub
        z
        log
    )
    target_compile_definitions(dng_sdk PRIVATE
        qDNGUseLibJPEG=1
        qDNGUseXMP=0
        qDNGUseJXL=0
        qDNGThreadSafe=1
        qDNGValidateTarget=1
        qAndroid=1
    )
endif()

# Main JNI library
add_library(${CMAKE_PROJECT_NAME} SHARED
    raw2dng_jni.cpp
    dng_converter.cpp
)

# Link libraries
if(DNG_SDK_SOURCES)
    target_link_libraries(${CMAKE_PROJECT_NAME}
        dng_sdk
        xmp_stub
        android
        log
        z
        jnigraphics
    )
else()
    target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
    )
endif()

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    qDNGUseLibJPEG=1
    qDNGUseXMP=0
    qDNGUseJXL=0
    qDNGThreadSafe=1
    qDNGValidateTarget=1
    qAndroid=1
)
