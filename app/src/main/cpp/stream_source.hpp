#pragma once
#include <cstddef>
#include <memory>
#include <string>

// Extra codecs feed decompressed bytes to libarchive, which can then recognize
// an archive container or expose the bytes as one standalone file.
class StreamSource {
public:
    virtual ~StreamSource() = default;
    virtual size_t read(const void** data) = 0;
    virtual const char* codec() const = 0;
};

std::unique_ptr<StreamSource> openExtraCodec(const std::string& path, const std::string& name);
std::string standaloneName(const std::string& name, size_t layers);
