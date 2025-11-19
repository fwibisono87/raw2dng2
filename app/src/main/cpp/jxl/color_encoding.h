// Stub header for JXL color_encoding.h
// This is a dummy file to allow compilation when qDNGUseJXL=0
// The DNG SDK won't actually use JXL functionality

#ifndef JXL_COLOR_ENCODING_H_STUB
#define JXL_COLOR_ENCODING_H_STUB

#include <cstdint>

// Stub type to satisfy DNG SDK's JXL type references
// This is never actually used when qDNGUseJXL=0
struct JxlColorEncoding {
    // Minimal stub - just needs to be a valid type
    uint32_t dummy;
};

#endif // JXL_COLOR_ENCODING_H_STUB
