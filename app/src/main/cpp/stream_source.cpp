#include "stream_source.hpp"
#include <brotli/decode.h>
#include <snappy.h>
#include <zlib.h>
#include <algorithm>
#include <array>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <vector>

namespace {
constexpr size_t BLOCK = 65536;
constexpr uint64_t LIMIT = 10ULL * 1024 * 1024 * 1024;
struct FileCloser { void operator()(FILE* file) const { fclose(file); } };
using File = std::unique_ptr<FILE, FileCloser>;
File openFile(const std::string& path) {
    File file(fopen(path.c_str(), "rb"));
    if (!file) throw std::runtime_error("Could not open compressed input.");
    return file;
}
size_t readFile(FILE* file, void* data, size_t length) {
    size_t count = fread(data, 1, length, file);
    if (ferror(file)) throw std::runtime_error("Could not read compressed input.");
    return count;
}
void exact(FILE* file, void* data, size_t length) {
    if (readFile(file, data, length) != length) throw std::runtime_error("Truncated compressed stream.");
}
std::string lower(std::string name) {
    std::transform(name.begin(), name.end(), name.begin(), [](unsigned char c) { return std::tolower(c); });
    return name;
}
bool ends(const std::string& name, const std::string& suffix) {
    return name.size() >= suffix.size() && name.compare(name.size() - suffix.size(), suffix.size(), suffix) == 0;
}
class BufferedSource : public StreamSource {
protected:
    File file;
    std::array<uint8_t, BLOCK> input{}, output{};
    uint64_t total = 0;
    bool done = false;
    explicit BufferedSource(const std::string& path) : file(openFile(path)) {}
    size_t emit(const void** data, size_t size) {
        total += size;
        if (total > LIMIT) throw std::runtime_error("Decompression limit is 10 GiB.");
        *data = output.data();
        return size;
    }
};
class BrotliSource final : public BufferedSource {
    std::unique_ptr<BrotliDecoderState, decltype(&BrotliDecoderDestroyInstance)> decoder;
    size_t available = 0;
    const uint8_t* next = nullptr;
public:
    explicit BrotliSource(const std::string& path) : BufferedSource(path),
        decoder(BrotliDecoderCreateInstance(nullptr, nullptr, nullptr), BrotliDecoderDestroyInstance) {
        if (!decoder) throw std::runtime_error("Could not allocate Brotli decoder.");
    }
    const char* codec() const override { return "Brotli"; }
    size_t read(const void** data) override {
        if (done) return emit(data, 0);
        size_t space = output.size();
        uint8_t* target = output.data();
        while (space == output.size()) {
            if (available == 0) {
                available = readFile(file.get(), input.data(), input.size());
                next = input.data();
            }
            const auto result = BrotliDecoderDecompressStream(decoder.get(), &available, &next, &space, &target, nullptr);
            if (result == BROTLI_DECODER_RESULT_ERROR) throw std::runtime_error("Invalid Brotli stream.");
            if (result == BROTLI_DECODER_RESULT_SUCCESS) {
                if (available != 0 || fgetc(file.get()) != EOF) throw std::runtime_error("Trailing bytes after Brotli stream.");
                done = true;
                break;
            }
            if (result == BROTLI_DECODER_RESULT_NEEDS_MORE_INPUT && available == 0 && feof(file.get()))
                throw std::runtime_error("Truncated Brotli stream.");
        }
        return emit(data, output.size() - space);
    }
};
class DeflateSource final : public BufferedSource {
    z_stream stream{};
    bool initialized = false;
    int mode;
public:
    DeflateSource(const std::string& path, int mode) : BufferedSource(path), mode(mode) {
        if (inflateInit2(&stream, mode) != Z_OK)
            throw std::runtime_error("Could not allocate DEFLATE decoder.");
        initialized = true;
    }
    ~DeflateSource() override { if (initialized) inflateEnd(&stream); }
    const char* codec() const override { return mode < 0 ? "Raw DEFLATE" : mode == 31 ? "gzip" : "zlib"; }
    size_t read(const void** data) override {
        if (done) return emit(data, 0);
        stream.next_out = output.data();
        stream.avail_out = output.size();
        while (stream.avail_out == output.size()) {
            if (stream.avail_in == 0) {
                stream.avail_in = readFile(file.get(), input.data(), input.size());
                stream.next_in = input.data();
            }
            const auto result = inflate(&stream, Z_NO_FLUSH);
            if (result == Z_STREAM_END) {
                if (mode == 31) {
                    if (stream.avail_in == 0) {
                        stream.avail_in = readFile(file.get(), input.data(), input.size());
                        stream.next_in = input.data();
                    }
                    if (stream.avail_in == 0) { done = true; break; }
                    // RFC 1952 permits concatenated members; zlib validates each CRC.
                    auto* nextIn = stream.next_in;
                    auto availableIn = stream.avail_in;
                    auto* nextOut = stream.next_out;
                    auto availableOut = stream.avail_out;
                    if (inflateReset2(&stream, mode) != Z_OK) throw std::runtime_error("Could not reset gzip decoder.");
                    stream.next_in = nextIn;
                    stream.avail_in = availableIn;
                    stream.next_out = nextOut;
                    stream.avail_out = availableOut;
                    continue;
                } else {
                    if (stream.avail_in != 0 || fgetc(file.get()) != EOF) throw std::runtime_error("Trailing bytes after DEFLATE stream.");
                    done = true;
                    break;
                }
            }
            if (result != Z_OK && result != Z_BUF_ERROR) throw std::runtime_error("Invalid DEFLATE stream or checksum.");
            if (stream.avail_in == 0 && feof(file.get()) && stream.avail_out == output.size())
                throw std::runtime_error("Truncated DEFLATE stream.");
        }
        return emit(data, output.size() - stream.avail_out);
    }
};
uint32_t little32(const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}
uint32_t maskedCrc(const uint8_t* data, size_t size) {
    static const auto table = [] {
        std::array<uint32_t, 256> result{};
        for (uint32_t i = 0; i < 256; i++) {
            uint32_t crc = i;
            for (int bit = 0; bit < 8; bit++) crc = (crc >> 1) ^ ((crc & 1) ? 0x82f63b78U : 0);
            result[i] = crc;
        }
        return result;
    }();
    uint32_t crc = ~0U;
    for (size_t i = 0; i < size; i++) crc = table[(crc ^ data[i]) & 255] ^ (crc >> 8);
    crc = ~crc;
    return ((crc >> 15) | (crc << 17)) + 0xa282ead8U;
}
constexpr uint8_t SNAPPY_MAGIC[] = {0xff, 6, 0, 0, 's', 'N', 'a', 'P', 'p', 'Y'};
class SnappySource final : public BufferedSource {
    bool framed;
    std::vector<uint8_t> block;
    std::vector<uint8_t> rawOutput;
    size_t rawPosition = 0;
public:
    SnappySource(const std::string& path, bool framed) : BufferedSource(path), framed(framed) {
        if (framed) {
            uint8_t magic[10];
            exact(file.get(), magic, sizeof(magic));
            if (memcmp(magic, SNAPPY_MAGIC, sizeof(magic))) throw std::runtime_error("Invalid Snappy stream identifier.");
        } else {
            // Raw Snappy has one length-prefixed block, without a frame or checksum.
            constexpr size_t RAW_LIMIT = 64 * 1024 * 1024;
            size_t count;
            while ((count = readFile(file.get(), input.data(), input.size())) != 0) {
                if (block.size() + count > RAW_LIMIT) throw std::runtime_error("Raw Snappy block exceeds 64 MiB; use framed Snappy for large files.");
                block.insert(block.end(), input.begin(), input.begin() + count);
            }
            size_t length = 0;
            if (!snappy::GetUncompressedLength(reinterpret_cast<const char*>(block.data()), block.size(), &length)
                    || length > RAW_LIMIT) throw std::runtime_error("Invalid or oversized raw Snappy block.");
            rawOutput.resize(length);
            if (!snappy::RawUncompress(reinterpret_cast<const char*>(block.data()), block.size(), reinterpret_cast<char*>(rawOutput.data())))
                throw std::runtime_error("Invalid raw Snappy block.");
            block.clear();
            block.shrink_to_fit();
        }
    }
    const char* codec() const override { return framed ? "Snappy framed" : "Snappy raw"; }
    size_t read(const void** data) override {
        if (!framed) {
            size_t count = std::min(output.size(), rawOutput.size() - rawPosition);
            if (count) memcpy(output.data(), rawOutput.data() + rawPosition, count);
            rawPosition += count;
            return emit(data, count);
        }
        for (;;) {
            uint8_t header[4];
            size_t count = readFile(file.get(), header, sizeof(header));
            if (count == 0) return emit(data, 0);
            if (count != sizeof(header)) throw std::runtime_error("Truncated Snappy chunk header.");
            uint32_t length = uint32_t(header[1]) | uint32_t(header[2]) << 8 | uint32_t(header[3]) << 16;
            if (header[0] >= 0x80 && header[0] != 0xff) {
                while (length) {
                    size_t amount = std::min<size_t>(length, input.size());
                    exact(file.get(), input.data(), amount);
                    length -= amount;
                }
                continue;
            }
            if (header[0] == 0xff) {
                uint8_t identifier[6];
                if (length != sizeof(identifier)) throw std::runtime_error("Invalid Snappy identifier length.");
                exact(file.get(), identifier, sizeof(identifier));
                if (memcmp(identifier, SNAPPY_MAGIC + 4, sizeof(identifier))) throw std::runtime_error("Invalid Snappy identifier.");
                continue;
            }
            if (header[0] > 1 || length < 4 || length > snappy::MaxCompressedLength(BLOCK) + 4)
                throw std::runtime_error("Invalid or unsupported Snappy chunk.");
            block.resize(length);
            exact(file.get(), block.data(), block.size());
            size_t size = length - 4;
            if (header[0] == 0) {
                if (!snappy::GetUncompressedLength(reinterpret_cast<const char*>(block.data() + 4), size, &size)
                        || size > output.size()) throw std::runtime_error("Invalid Snappy block length.");
                if (!snappy::RawUncompress(reinterpret_cast<const char*>(block.data() + 4), length - 4, reinterpret_cast<char*>(output.data())))
                    throw std::runtime_error("Invalid Snappy compressed block.");
            } else {
                if (size > output.size()) throw std::runtime_error("Oversized Snappy uncompressed chunk.");
                memcpy(output.data(), block.data() + 4, size);
            }
            if (maskedCrc(output.data(), size) != little32(block.data())) throw std::runtime_error("Snappy checksum mismatch.");
            if (size) return emit(data, size);
        }
    }
};
}

std::unique_ptr<StreamSource> openExtraCodec(const std::string& path, const std::string& name) {
    auto file = openFile(path);
    uint8_t magic[10]{};
    size_t count = readFile(file.get(), magic, sizeof(magic));
    if (count >= 2 && magic[0] == 0x1f && magic[1] == 0x8b)
        return std::make_unique<DeflateSource>(path, 31);
    if (count == sizeof(magic) && memcmp(magic, SNAPPY_MAGIC, sizeof(magic)) == 0)
        return std::make_unique<SnappySource>(path, true);
    const auto suffix = lower(name);
    if (ends(suffix, ".br")) return std::make_unique<BrotliSource>(path);
    if (ends(suffix, ".sz")) return std::make_unique<SnappySource>(path, true);
    if (ends(suffix, ".snappy")) return std::make_unique<SnappySource>(path, false);
    if (ends(suffix, ".deflate")) return std::make_unique<DeflateSource>(path, -MAX_WBITS);
    const bool zlibHeader = count >= 2 && (magic[0] & 15) == 8 && (magic[0] >> 4) <= 7
        && ((uint32_t(magic[0]) << 8) | magic[1]) % 31 == 0;
    if (ends(suffix, ".zlib") || ends(suffix, ".zz") || zlibHeader)
        return std::make_unique<DeflateSource>(path, MAX_WBITS);
    return nullptr;
}

std::string standaloneName(const std::string& displayName, size_t layers) {
    auto separator = displayName.find_last_of("/\\");
    std::string name = displayName.substr(separator == std::string::npos ? 0 : separator + 1);
    for (size_t layer = 0; layer < layers; layer++) {
        const auto normalized = lower(name);
        bool removed = false;
        for (const char* suffix : {".gzip", ".gz", ".bzip2", ".bz2", ".bz", ".xz", ".lzma", ".zstd", ".zst",
                                   ".lz4", ".lzip", ".lz", ".lzo", ".z", ".br", ".sz", ".snappy", ".zlib", ".zz", ".deflate", ".uu", ".uue"}) {
            if (ends(normalized, suffix)) {
                name.resize(name.size() - strlen(suffix));
                removed = true;
                break;
            }
        }
        if (!removed) break;
    }
    return name.empty() || name == "." || name == ".." ? "decompressed" : name;
}
