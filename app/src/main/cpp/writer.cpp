#include <jni.h>
#include <archive.h>
#include <archive_entry.h>
#include <brotli/encode.h>
#include <snappy.h>
#include <zlib.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr uint64_t LIMIT = 10ULL * 1024 * 1024 * 1024;
constexpr size_t BLOCK = 65536;

void fail(JNIEnv* env, const char* message) { env->ThrowNew(env->FindClass("java/io/IOException"), message); }
std::string text(JNIEnv* env, jbyteArray bytes) {
    std::string value(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<jbyte*>(value.data()));
    if (value.find('\0') != std::string::npos) throw std::runtime_error("Embedded NUL in writer argument.");
    return value;
}
void checked(archive* value, int result) {
    if (result != ARCHIVE_OK) throw std::runtime_error(archive_error_string(value) ? archive_error_string(value) : "Archive creation failed.");
}
uint32_t crc32c(const uint8_t* data, size_t size) {
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

struct Writer {
    JNIEnv* env;
    jobject owner;
    jfieldID cancelled;
    archive* value = archive_write_new();
    FILE* output = nullptr;
    std::string format, codec;
    uint64_t outputSize = 0, inputSize = 0;
    int64_t remaining = 0;
    bool entryOpen = false, finished = false, hasEntry = false;
    std::unique_ptr<BrotliEncoderState, decltype(&BrotliEncoderDestroyInstance)> brotli{nullptr, BrotliEncoderDestroyInstance};
    z_stream deflate{};
    bool deflateReady = false;
    std::vector<uint8_t> pending;

    Writer(JNIEnv* env, jobject instance) : env(env), owner(env->NewGlobalRef(instance)),
        cancelled(env->GetFieldID(env->GetObjectClass(instance), "cancelled", "Z")) {}
    ~Writer() {
        if (value) {
            if (!finished) archive_write_fail(value);
            archive_write_free(value);
        }
        if (deflateReady) deflateEnd(&deflate);
        if (output) fclose(output);
        env->DeleteGlobalRef(owner);
    }
    void check() {
        if (env->GetBooleanField(owner, cancelled)) throw std::runtime_error("Operation cancelled.");
        if (finished) throw std::runtime_error("Archive writer is already finished.");
    }
    void emit(const void* data, size_t length) {
        check();
        if (length > LIMIT - outputSize) throw std::runtime_error("Output limit is 10 GiB.");
        if (length && fwrite(data, 1, length, output) != length) throw std::runtime_error("Could not write archive output; check free space.");
        outputSize += length;
    }
    void snappyBlock() {
        if (pending.empty()) return;
        std::string compressed;
        snappy::Compress(reinterpret_cast<const char*>(pending.data()), pending.size(), &compressed);
        const bool useCompressed = compressed.size() < pending.size();
        const size_t size = useCompressed ? compressed.size() : pending.size();
        uint8_t header[8]{};
        header[0] = useCompressed ? 0 : 1;
        const uint32_t length = static_cast<uint32_t>(size + 4);
        for (int i = 0; i < 3; i++) header[1 + i] = (length >> (8 * i)) & 255;
        const auto crc = crc32c(pending.data(), pending.size());
        for (int i = 0; i < 4; i++) header[4 + i] = (crc >> (8 * i)) & 255;
        emit(header, sizeof(header));
        emit(useCompressed ? static_cast<const void*>(compressed.data()) : pending.data(), size);
        pending.clear();
    }
    void encode(const uint8_t* data, size_t length, bool finish) {
        check();
        std::array<uint8_t, BLOCK> buffer;
        if (codec == "brotli") {
            size_t available = length;
            const uint8_t* next = data;
            do {
                check();
                size_t space = buffer.size();
                uint8_t* target = buffer.data();
                if (!BrotliEncoderCompressStream(brotli.get(), finish ? BROTLI_OPERATION_FINISH : BROTLI_OPERATION_PROCESS,
                        &available, &next, &space, &target, nullptr)) throw std::runtime_error("Brotli compression failed.");
                emit(buffer.data(), buffer.size() - space);
                if (finish ? BrotliEncoderIsFinished(brotli.get()) : available == 0 && !BrotliEncoderHasMoreOutput(brotli.get())) break;
            } while (true);
        } else if (codec == "snappy") {
            while (length) {
                size_t amount = std::min(BLOCK - pending.size(), length);
                pending.insert(pending.end(), data, data + amount);
                data += amount;
                length -= amount;
                if (pending.size() == BLOCK) snappyBlock();
            }
            if (finish) snappyBlock();
        } else if (codec == "zlib" || (format != "zip" && format != "7zip" && codec == "deflate")) {
            deflate.next_in = const_cast<Bytef*>(data);
            deflate.avail_in = static_cast<uInt>(length);
            do {
                check();
                deflate.next_out = buffer.data();
                deflate.avail_out = buffer.size();
                const int result = ::deflate(&deflate, finish ? Z_FINISH : Z_NO_FLUSH);
                if (result != Z_OK && result != Z_STREAM_END) throw std::runtime_error("DEFLATE compression failed.");
                emit(buffer.data(), buffer.size() - deflate.avail_out);
                if (finish ? result == Z_STREAM_END : deflate.avail_in == 0 && deflate.avail_out > 0) break;
            } while (true);
        } else {
            emit(data, length);
        }
    }
    void endEntry() {
        check();
        if (!entryOpen) throw std::runtime_error("No archive entry is open.");
        if (remaining != 0) throw std::runtime_error("Input file ended before its declared size.");
        checked(value, archive_write_finish_entry(value));
        entryOpen = false;
    }
};

la_ssize_t outputCallback(archive* value, void* context, const void* data, size_t length) {
    try {
        auto* writer = static_cast<Writer*>(context);
        const auto* next = static_cast<const uint8_t*>(data);
        size_t remaining = length;
        while (remaining) {
            const size_t amount = std::min(BLOCK, remaining);
            writer->encode(next, amount, false);
            next += amount;
            remaining -= amount;
        }
        return length;
    } catch (const std::exception& error) {
        archive_set_error(value, EIO, "%s", error.what());
        return -1;
    }
}
Writer* get(JNIEnv* env, jlong handle) {
    if (!handle) throw std::runtime_error("Archive writer is closed.");
    return reinterpret_cast<Writer*>(handle);
}
void pathCheck(const std::string& path) {
    if (path.empty() || path[0] == '/' || path.find('\\') != std::string::npos || path.find(':') != std::string::npos)
        throw std::runtime_error("Unsafe output entry path.");
    size_t start = 0, count = 0;
    while (start < path.size()) {
        auto end = path.find('/', start);
        if (end == std::string::npos) end = path.size();
        const auto part = path.substr(start, end - start);
        if (part.empty() || part == "." || part == ".." || ++count > 64)
            throw std::runtime_error("Unsafe output entry path.");
        start = end + 1;
    }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_aarchive_NativeWriter_open(JNIEnv* env, jclass, jbyteArray path, jbyteArray temporaryDirectory,
                                   jbyteArray formatBytes, jbyteArray codecBytes, jint level, jobject owner) {
    try {
        auto writer = std::make_unique<Writer>(env, owner);
        auto* a = writer->value;
        if (!a) throw std::runtime_error("Could not allocate archive writer.");
        writer->format = text(env, formatBytes);
        writer->codec = text(env, codecBytes);
        const auto& format = writer->format;
        const auto& codec = writer->codec;
        if (level < 1 || level > 9) throw std::runtime_error("Compression level must be between 1 and 9.");
        if (setenv("TMPDIR", text(env, temporaryDirectory).c_str(), 1) != 0)
            throw std::runtime_error("Could not configure private temporary storage.");
        if (format == "zip") checked(a, archive_write_set_format_zip(a));
        else if (format == "7zip") checked(a, archive_write_set_format_7zip(a));
        else if (format == "tar") checked(a, archive_write_set_format_pax_restricted(a));
        else if (format == "cpio") checked(a, archive_write_set_format_cpio_newc(a));
        else if (format == "raw") checked(a, archive_write_set_format_raw(a));
        else throw std::runtime_error("Unsupported output archive format.");

        const std::string quality = std::to_string(level);
        if (format == "zip" || format == "7zip") {
            checked(a, archive_write_add_filter_none(a));
            checked(a, archive_write_set_format_option(a, format == "zip" ? "zip" : "7zip", "compression", codec.c_str()));
            if (codec != "store") checked(a, archive_write_set_format_option(a, format == "zip" ? "zip" : "7zip", "compression-level", quality.c_str()));
        } else {
            int result;
            if (codec == "none") {
                if (format == "raw") throw std::runtime_error("Standalone output requires compression or encoding.");
                result = archive_write_add_filter_none(a);
            } else if (codec == "gzip") result = archive_write_add_filter_gzip(a);
            else if (codec == "bzip2") result = archive_write_add_filter_bzip2(a);
            else if (codec == "xz") result = archive_write_add_filter_xz(a);
            else if (codec == "lzma") result = archive_write_add_filter_lzma(a);
            else if (codec == "zstd") result = archive_write_add_filter_zstd(a);
            else if (codec == "lz4") result = archive_write_add_filter_lz4(a);
            else if (codec == "lzip") result = archive_write_add_filter_lzip(a);
            else if (codec == "lzop") result = archive_write_add_filter_lzop(a);
            else if (codec == "compress") result = archive_write_add_filter_compress(a);
            else if (codec == "uuencode") result = archive_write_add_filter_uuencode(a);
            else if (codec == "brotli" || codec == "snappy" || codec == "zlib" || codec == "deflate") result = archive_write_add_filter_none(a);
            else throw std::runtime_error("Unsupported output compression codec.");
            checked(a, result); // Reject external-program fallbacks (ARCHIVE_WARN).
            if (codec == "gzip" || codec == "bzip2" || codec == "xz" || codec == "lzma" || codec == "zstd" || codec == "lz4" || codec == "lzip")
                checked(a, archive_write_set_filter_option(a, codec.c_str(), "compression-level", quality.c_str()));
        }
        // Exact file lengths matter for standalone streams; do not pad a final block.
        checked(a, archive_write_set_bytes_per_block(a, 0));
        checked(a, archive_write_set_bytes_in_last_block(a, 1));
        if (codec == "brotli") {
            writer->brotli.reset(BrotliEncoderCreateInstance(nullptr, nullptr, nullptr));
            if (!writer->brotli || !BrotliEncoderSetParameter(writer->brotli.get(), BROTLI_PARAM_QUALITY, level))
                throw std::runtime_error("Could not allocate Brotli encoder.");
        } else if (codec == "zlib" || (format != "zip" && format != "7zip" && codec == "deflate")) {
            if (deflateInit2(&writer->deflate, level, Z_DEFLATED, codec == "zlib" ? MAX_WBITS : -MAX_WBITS, 8, Z_DEFAULT_STRATEGY) != Z_OK)
                throw std::runtime_error("Could not allocate DEFLATE encoder.");
            writer->deflateReady = true;
        }
        writer->output = fopen(text(env, path).c_str(), "wb");
        if (!writer->output) throw std::runtime_error("Could not create archive output.");
        if (codec == "snappy") {
            const uint8_t magic[] = {0xff, 6, 0, 0, 's', 'N', 'a', 'P', 'p', 'Y'};
            writer->emit(magic, sizeof(magic));
        }
        checked(a, archive_write_open(a, writer.get(), nullptr, outputCallback, nullptr));
        return reinterpret_cast<jlong>(writer.release());
    } catch (const std::exception& error) { fail(env, error.what()); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_aarchive_NativeWriter_begin(JNIEnv* env, jclass, jlong handle, jbyteArray pathBytes, jlong size, jlong modified, jboolean directory) {
    try {
        auto* writer = get(env, handle);
        writer->check();
        if (writer->entryOpen) throw std::runtime_error("Finish the previous entry first.");
        auto path = text(env, pathBytes);
        pathCheck(path);
        if (size < 0 || static_cast<uint64_t>(size) > LIMIT || (directory && size != 0)) throw std::runtime_error("Invalid entry size.");
        if (writer->format == "cpio" && size > 0xffffffffLL) throw std::runtime_error("CPIO files must be smaller than 4 GiB.");
        if (writer->format == "raw" && (directory || writer->hasEntry)) throw std::runtime_error("Standalone compression accepts exactly one regular file.");
        if (static_cast<uint64_t>(size) > LIMIT - writer->inputSize) throw std::runtime_error("Creation input limit is 10 GiB.");
        if (writer->format == "zip") {
            // Empty files need no compressor. This also avoids libarchive's
            // ZIP/LZMA writer omitting the method header for an empty entry.
            int result;
            const auto codec = directory || size == 0 ? std::string("store") : writer->codec;
            if (codec == "store") result = archive_write_zip_set_compression_store(writer->value);
            else if (codec == "deflate") result = archive_write_zip_set_compression_deflate(writer->value);
            else if (codec == "bzip2") result = archive_write_zip_set_compression_bzip2(writer->value);
            else if (codec == "lzma") result = archive_write_zip_set_compression_lzma(writer->value);
            else if (codec == "xz") result = archive_write_zip_set_compression_xz(writer->value);
            else if (codec == "zstd") result = archive_write_zip_set_compression_zstd(writer->value);
            else throw std::runtime_error("Unsupported ZIP compression.");
            checked(writer->value, result);
        }
        if (directory && path.back() != '/') path += '/';
        std::unique_ptr<archive_entry, decltype(&archive_entry_free)> entry(archive_entry_new(), archive_entry_free);
        if (!entry) throw std::runtime_error("Could not allocate archive entry.");
        archive_entry_set_pathname_utf8(entry.get(), path.c_str());
        archive_entry_set_filetype(entry.get(), directory ? AE_IFDIR : AE_IFREG);
        archive_entry_set_perm(entry.get(), directory ? 0755 : 0644);
        archive_entry_set_size(entry.get(), size);
        archive_entry_set_mtime(entry.get(), modified / 1000, (modified % 1000) * 1000000);
        checked(writer->value, archive_write_header(writer->value, entry.get()));
        writer->inputSize += size;
        writer->remaining = size;
        writer->entryOpen = writer->hasEntry = true;
    } catch (const std::exception& error) { fail(env, error.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_aarchive_NativeWriter_write(JNIEnv* env, jclass, jlong handle, jbyteArray bytes, jint length) {
    try {
        auto* writer = get(env, handle);
        writer->check();
        if (!writer->entryOpen || length < 0 || length > env->GetArrayLength(bytes) || length > 1024 * 1024 || length > writer->remaining)
            throw std::runtime_error("Invalid entry data length.");
        std::vector<uint8_t> buffer(length);
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(buffer.data()));
        size_t offset = 0;
        while (offset < buffer.size()) {
            const auto count = archive_write_data(writer->value, buffer.data() + offset, buffer.size() - offset);
            if (count <= 0) throw std::runtime_error(archive_error_string(writer->value) ? archive_error_string(writer->value) : "Could not write archive entry.");
            offset += count;
            writer->remaining -= count;
        }
    } catch (const std::exception& error) { fail(env, error.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_aarchive_NativeWriter_endEntry(JNIEnv* env, jclass, jlong handle) {
    try { get(env, handle)->endEntry(); } catch (const std::exception& error) { fail(env, error.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_aarchive_NativeWriter_finish(JNIEnv* env, jclass, jlong handle) {
    try {
        auto* writer = get(env, handle);
        writer->check();
        if (writer->entryOpen) writer->endEntry();
        if (writer->format == "raw" && !writer->hasEntry) throw std::runtime_error("Standalone compression requires one file.");
        checked(writer->value, archive_write_close(writer->value));
        writer->encode(nullptr, 0, true);
        if (fflush(writer->output) != 0) throw std::runtime_error("Could not flush archive output; check free space.");
        FILE* output = writer->output;
        writer->output = nullptr;
        if (fclose(output) != 0) throw std::runtime_error("Could not close archive output.");
        writer->finished = true;
    } catch (const std::exception& error) { fail(env, error.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_aarchive_NativeWriter_free(JNIEnv*, jclass, jlong handle) { delete reinterpret_cast<Writer*>(handle); }
