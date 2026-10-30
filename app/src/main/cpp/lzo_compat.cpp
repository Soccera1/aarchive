#include <lzo/lzo1x.h>
#include <lzokay.hpp>
#include <zlib.h>
#include <new>

extern "C" {
int lzo_init() { return LZO_E_OK; }
unsigned lzo_version() { return 0x20a0; } // LZO 2.10 API compatibility, not the LZOKay release.
const char* lzo_version_string() { return "LZOKay compatibility backend"; }
lzo_uint32 lzo_adler32(lzo_uint32 checksum, const unsigned char* input, lzo_uint length) {
    while (length) {
        const auto count = static_cast<uInt>(length > 65536 ? 65536 : length);
        checksum = static_cast<lzo_uint32>(adler32(checksum, input, count));
        input += count;
        length -= count;
    }
    return checksum;
}
int lzo1x_decompress_safe(const unsigned char* input, lzo_uint length,
                        unsigned char* output, lzo_uint* size, void*) {
    size_t actual = 0;
    auto result = lzokay::decompress(input, length, output, *size, actual);
    *size = actual;
    return result == lzokay::EResult::Success ? LZO_E_OK : LZO_E_ERROR;
}
int lzo1x_1_compress(const unsigned char* input, lzo_uint length,
                    unsigned char* output, lzo_uint* size, void*) {
    try {
        size_t actual = 0;
        auto result = lzokay::compress(input, length, output, lzokay::compress_worst_size(length), actual);
        *size = actual;
        return result == lzokay::EResult::Success ? LZO_E_OK : LZO_E_ERROR;
    } catch (const std::bad_alloc&) { return LZO_E_OUT_OF_MEMORY; }
}
int lzo1x_1_15_compress(const unsigned char* input, lzo_uint length,
                       unsigned char* output, lzo_uint* size, void* workspace) {
    return lzo1x_1_compress(input, length, output, size, workspace);
}
int lzo1x_999_compress_level(const unsigned char* input, lzo_uint length,
                           unsigned char* output, lzo_uint* size, void* workspace,
                           const unsigned char*, lzo_uint, void*, int) {
    return lzo1x_1_compress(input, length, output, size, workspace);
}
}
