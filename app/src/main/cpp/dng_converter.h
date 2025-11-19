#ifndef DNG_CONVERTER_H
#define DNG_CONVERTER_H

#include <string>

class DNGConverter {
public:
    /**
     * Convert a RAW file to DNG format
     * @param inputPath Path to the input RAW file (CR2, ARW, etc.)
     * @param outputPath Path where the DNG file will be saved
     * @return true if conversion successful, false otherwise
     */
    static bool convertToDNG(const std::string& inputPath, const std::string& outputPath, std::string& errorMessage);

private:
    static bool isDNGSDKAvailable();
};

#endif // DNG_CONVERTER_H
