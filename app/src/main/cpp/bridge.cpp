#include <jni.h>
#include <archive.h>
#include <archive_entry.h>
#include "stream_source.hpp"
#include <cerrno>
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Reader {
    archive* value = archive_read_new();
    std::unique_ptr<FILE, decltype(&fclose)> decoded{nullptr, fclose};
    std::string outerCompression;
    std::string name;
    archive_entry* pending = nullptr;
    bool first = true;
    bool standalone = false;
    bool empty = false;
    ~Reader() { if (value) archive_read_free(value); }
};

la_ssize_t sourceRead(archive* value, void* context, const void** data) {
    try { return static_cast<StreamSource*>(context)->read(data); }
    catch (const std::exception& error) {
        archive_set_error(value, EIO, "%s", error.what());
        return -1;
    }
}
void supportFilters(archive* a) {
    // Explicit native filters only: never register external helper programs.
    for (auto support : {archive_read_support_filter_none, archive_read_support_filter_gzip,
            archive_read_support_filter_bzip2, archive_read_support_filter_xz,
            archive_read_support_filter_lzma, archive_read_support_filter_zstd,
            archive_read_support_filter_lz4, archive_read_support_filter_lzip,
            archive_read_support_filter_lzop, archive_read_support_filter_compress,
            archive_read_support_filter_uu, archive_read_support_filter_rpm}) {
        if (support(a) != ARCHIVE_OK)
            throw std::runtime_error(std::string("Compression decoder unavailable: ")
                + (archive_error_string(a) ? archive_error_string(a) : "unknown decoder"));
    }
}

void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message ? message : "Archive operation failed");
}

std::string bytes(JNIEnv* env, jbyteArray value) {
    const auto length = env->GetArrayLength(value);
    std::string result(length, '\0');
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(result.data()));
    return result;
}

jstring utf8(JNIEnv* env, const char* value) {
    std::string text = value ? value : "";
    auto data = env->NewByteArray(static_cast<jsize>(text.size()));
    if (!data) return nullptr;
    env->SetByteArrayRegion(data, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
    auto cls = env->FindClass("java/lang/String");
    auto constructor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    auto encoding = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(cls, constructor, data, encoding));
    env->DeleteLocalRef(data);
    env->DeleteLocalRef(encoding);
    return result;
}

Reader* reader(JNIEnv* env, jlong handle) {
    if (!handle) { fail(env, "Archive is closed"); return nullptr; }
    return reinterpret_cast<Reader*>(handle);
}

using File = std::unique_ptr<FILE, decltype(&fclose)>;

std::runtime_error archiveFailure(archive* a) {
    const char* message = archive_error_string(a);
    return std::runtime_error(message ? message : "Could not read archive layer.");
}

struct Layers {
    JNIEnv* env;
    jobject cancellation;
    jfieldID cancelled;
    std::string temporaryPrefix, password;
    uint64_t expanded = 0;

    Layers(JNIEnv* env, jobject cancellation, const std::string& input, std::string password)
        : env(env), cancellation(cancellation),
          cancelled(cancellation ? env->GetFieldID(env->GetObjectClass(cancellation), "cancelled", "Z") : nullptr),
          temporaryPrefix(input + ".decoded-"), password(std::move(password)) {}

    void check(size_t depth) {
        if (cancellation && env->GetBooleanField(cancellation, cancelled)) throw std::runtime_error("Operation cancelled.");
        if (depth > 32) throw std::runtime_error("Archive nesting limit is 32 layers.");
    }
    File cache() {
        // The imported file is private. Unlink each temporary file immediately:
        // closing it, any failure, or process death then releases the storage.
        auto name = temporaryPrefix + "XXXXXX";
        int fd = mkstemp(name.data());
        if (fd < 0) throw std::runtime_error("Could not create archive cache; check free space.");
        if (unlink(name.c_str()) != 0) {
            close(fd);
            throw std::runtime_error("Could not secure archive cache.");
        }
        File file(fdopen(fd, "w+b"), fclose);
        if (!file) { close(fd); throw std::runtime_error("Could not open archive cache."); }
        return file;
    }
    File copy(archive* a, size_t depth) {
        File file = cache();
        unsigned char buffer[65536];
        for (;;) {
            check(depth);
            auto size = archive_read_data(a, buffer, sizeof(buffer));
            if (size < 0) throw archiveFailure(a);
            if (size == 0) break;
            constexpr uint64_t limit = 10ULL * 1024 * 1024 * 1024;
            if (static_cast<uint64_t>(size) > limit - expanded)
                throw std::runtime_error("Expanded archive cache limit is 10 GiB across all layers.");
            if (fwrite(buffer, 1, size, file.get()) != static_cast<size_t>(size))
                throw std::runtime_error("Could not write archive cache; check free space.");
            expanded += size;
        }
        if (fflush(file.get()) != 0) throw std::runtime_error("Could not finish archive cache.");
        return file;
    }
};

std::string cachedPath(FILE* file) {
    // Reopening our unlinked inode gives every codec its own offset, and gives
    // libarchive a regular seekable file for any container that needs it.
    return "/proc/self/fd/" + std::to_string(fileno(file));
}

void supportFormats(archive* a, const std::string& name) {
    for (auto support : {archive_read_support_format_ar, archive_read_support_format_cpio,
            archive_read_support_format_empty, archive_read_support_format_lha,
            archive_read_support_format_tar, archive_read_support_format_xar,
            archive_read_support_format_warc, archive_read_support_format_7zip,
            archive_read_support_format_cab, archive_read_support_format_rar,
            archive_read_support_format_rar5, archive_read_support_format_iso9660,
            archive_read_support_format_zip}) {
        if (support(a) != ARCHIVE_OK) throw archiveFailure(a);
    }
    // Mtree's text heuristic also matches CSV. It needs an explicit name hint.
    auto hint = standaloneName(name, 64);
    std::transform(hint.begin(), hint.end(), hint.begin(), [](unsigned char c) { return std::tolower(c); });
    if (hint == "mtree" || hint == "metalog" || (hint.size() >= 6 && hint.compare(hint.size() - 6, 6, ".mtree") == 0))
        archive_read_support_format_mtree(a);
    archive_read_support_format_raw(a);
}

std::unique_ptr<Reader> openContainer(Layers& layers, const std::string& path, const std::string& name, bool allowRaw) {
    auto instance = std::make_unique<Reader>();
    auto* a = instance->value;
    if (!a) throw std::runtime_error("Could not allocate archive reader.");
    supportFormats(a, name);
    archive_read_support_filter_none(a);
    if (!layers.password.empty()) archive_read_add_passphrase(a, layers.password.c_str());
    if (archive_read_open_filename(a, path.c_str(), 65536) != ARCHIVE_OK) throw archiveFailure(a);
    int result = archive_read_next_header(a, &instance->pending);
    if (result != ARCHIVE_OK && result != ARCHIVE_EOF) throw archiveFailure(a);
    const auto format = archive_format(a);
    if (!allowRaw && (format == ARCHIVE_FORMAT_RAW || format == ARCHIVE_FORMAT_EMPTY)) return nullptr;
    instance->standalone = format == ARCHIVE_FORMAT_RAW || (format == ARCHIVE_FORMAT_EMPTY && allowRaw);
    instance->empty = result == ARCHIVE_EOF;
    instance->name = standaloneName(name, 0);
    return instance;
}

bool safeNestedEntry(archive_entry* entry) {
    if (archive_entry_symlink(entry) || archive_entry_hardlink(entry)) return false;
    auto type = archive_entry_filetype(entry);
    if (type != AE_IFREG && type != AE_IFDIR) return false;
    const char* value = archive_entry_pathname_utf8(entry);
    if (!value) value = archive_entry_pathname(entry);
    if (!value || !*value) return false;
    std::string path(value);
    if (path[0] == '/' || path.find('\\') != std::string::npos || path.find(':') != std::string::npos) return false;
    size_t start = 0, count = 0;
    while (start < path.size()) {
        auto end = path.find('/', start);
        if (end == std::string::npos) end = path.size();
        if (path.substr(start, end - start) == ".." || ++count > 64) return false;
        start = end + 1;
    }
    return true;
}

std::unique_ptr<Reader> openLayer(Layers& layers, const std::string& path, const std::string& name,
                                File backing, size_t depth, bool allowRaw) {
    layers.check(depth);
    // Decode the next stream using codec signatures, then let all registered
    // archive bidders inspect its bytes. No container names are special-cased.
    std::unique_ptr<StreamSource> source;
    auto openProbe = [&] {
        std::unique_ptr<archive, decltype(&archive_read_free)> probe(archive_read_new(), archive_read_free);
        if (!probe) throw std::runtime_error("Could not allocate compression reader.");
        auto* a = probe.get();
        supportFilters(a);
        archive_read_support_format_raw(a);
        archive_read_support_format_empty(a);
        int result = source ? archive_read_open(a, source.get(), nullptr, sourceRead, nullptr)
                            : archive_read_open_filename(a, path.c_str(), 65536);
        if (result != ARCHIVE_OK) throw archiveFailure(a);
        return probe;
    };
    auto probe = openProbe();
    auto* a = probe.get();
    std::unique_ptr<Reader> current;
    if (archive_filter_code(a, 0) != ARCHIVE_FILTER_NONE) {
        // Prefer the strict extra gzip decoder where its magic matches, while
        // keeping extension hints from overriding an already identified codec.
        source = openExtraCodec(path, "");
    } else {
        // Framed Snappy/zlib have signatures; Brotli, raw Snappy and raw
        // DEFLATE need their documented extension hints. Check these before
        // archive bidders can match an embedded header inside a literal block.
        source = openExtraCodec(path, name);
        if (!source) {
            current = openContainer(layers, path, name, allowRaw);
        }
    }
    if (source) {
        probe = openProbe();
        a = probe.get();
    }
    int result;
    size_t filters = source ? 1 : 0;
    std::string description = source ? std::string(" · ") + source->codec() : "";
    for (int i = 0; i < archive_filter_count(a); i++) {
        if (archive_filter_code(a, i) != ARCHIVE_FILTER_NONE) {
            filters++;
            if (archive_filter_name(a, i)) description += std::string(" · ") + archive_filter_name(a, i);
        }
    }
    if (filters) {
        layers.check(depth + filters);
        archive_entry* entry = nullptr;
        result = archive_read_next_header(a, &entry);
        if (result != ARCHIVE_OK && result != ARCHIVE_EOF) throw archiveFailure(a);
        File decoded = result == ARCHIVE_EOF ? layers.cache() : layers.copy(a, depth + filters);
        auto decodedPath = cachedPath(decoded.get());
        auto inner = openLayer(layers, decodedPath, standaloneName(name, filters), std::move(decoded), depth + filters, true);
        inner->outerCompression += description;
        return inner;
    }
    probe.reset();
    source.reset();
    if (!current) current = openContainer(layers, path, name, allowRaw);
    if (!current) return nullptr;
    if (current->standalone || current->empty) {
        current->decoded = std::move(backing);
        return current;
    }

    // Auto-descend only when the archive has one regular file (plus optional
    // directories), and that file itself is a recognized archive or stream.
    size_t files = 0, index = 0, fileIndex = 0;
    std::string nestedName;
    bool safe = true;
    archive_entry* entry = current->pending;
    do {
        layers.check(depth);
        if (index >= 100000) throw std::runtime_error("Archive contains more than 100,000 entries.");
        if (!safeNestedEntry(entry)) { safe = false; break; }
        if (archive_entry_filetype(entry) == AE_IFREG) {
            if (++files > 1) break;
            fileIndex = index;
            const char* utf8Name = archive_entry_pathname_utf8(entry);
            nestedName = utf8Name ? utf8Name : archive_entry_pathname(entry);
        }
        index++;
        result = archive_read_next_header(current->value, &entry);
        if (result != ARCHIVE_OK && result != ARCHIVE_EOF) throw archiveFailure(current->value);
    } while (result == ARCHIVE_OK);
    current = openContainer(layers, path, name, allowRaw);
    if (safe && files == 1 && result == ARCHIVE_EOF) {
        for (size_t i = 0; i < fileIndex; i++) {
            if (archive_read_next_header(current->value, &entry) != ARCHIVE_OK) throw archiveFailure(current->value);
        }
        File decoded = layers.copy(current->value, depth);
        auto decodedPath = cachedPath(decoded.get());
        auto inner = openLayer(layers, decodedPath, nestedName, std::move(decoded), depth + 1, false);
        if (inner) {
            const char* format = archive_format_name(current->value);
            inner->outerCompression += std::string(" · ") + (format ? format : "Archive");
            return inner;
        }
        current = openContainer(layers, path, name, allowRaw);
    }
    current->decoded = std::move(backing);
    return current;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_aarchive_NativeArchive_open(JNIEnv* env, jclass, jbyteArray path, jbyteArray password, jbyteArray displayName, jobject cancellation) {
    try {
        const auto input = bytes(env, path);
        Layers layers(env, cancellation, input, bytes(env, password));
        auto instance = openLayer(layers, input, bytes(env, displayName), File(nullptr, fclose), 0, false);
        if (!instance) throw std::runtime_error("Unrecognized archive or compressed stream.");
        return reinterpret_cast<jlong>(instance.release());
    } catch (const std::exception& error) {
        fail(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_aarchive_NativeArchive_next(JNIEnv* env, jclass, jlong handle) {
    auto* instance = reader(env, handle);
    if (!instance) return nullptr;
    auto* a = instance->value;
    archive_entry* entry = instance->pending;
    if (!instance->first) {
        if (instance->standalone || instance->empty) return nullptr;
        const int result = archive_read_next_header(a, &entry);
        if (result == ARCHIVE_EOF) return nullptr;
        if (result != ARCHIVE_OK) { fail(env, archive_error_string(a)); return nullptr; }
    }
    instance->first = false;
    if (instance->empty && !instance->standalone) return nullptr;
    auto cls = env->FindClass("dev/aarchive/ArchiveEntry");
    auto constructor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;JIZ)V");
    const char* path = instance->standalone ? instance->name.c_str() : archive_entry_pathname_utf8(entry);
    if (!path && entry) path = archive_entry_pathname(entry);
    auto name = utf8(env, path);
    if (!name || env->ExceptionCheck()) return nullptr;
    return env->NewObject(cls, constructor, name,
        static_cast<jlong>(instance->empty ? 0 : archive_entry_size_is_set(entry) ? archive_entry_size(entry) : -1),
        static_cast<jint>(instance->standalone ? AE_IFREG : archive_entry_filetype(entry)),
        static_cast<jboolean>(!instance->standalone && (archive_entry_symlink(entry) || archive_entry_hardlink(entry))));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_aarchive_NativeArchive_read(JNIEnv* env, jclass, jlong handle, jbyteArray buffer) {
    auto* instance = reader(env, handle);
    if (!instance) return -1;
    if (instance->empty) return 0;
    auto* a = instance->value;
    const auto length = env->GetArrayLength(buffer);
    if (length <= 0 || length > 1024 * 1024) { fail(env, "Invalid read buffer size"); return -1; }
    std::vector<char> data(length);
    const auto count = archive_read_data(a, data.data(), data.size());
    if (count < 0) { fail(env, archive_error_string(a)); return -1; }
    env->SetByteArrayRegion(buffer, 0, static_cast<jsize>(count), reinterpret_cast<jbyte*>(data.data()));
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_aarchive_NativeArchive_format(JNIEnv* env, jclass, jlong handle) {
    auto* instance = reader(env, handle);
    if (!instance) return nullptr;
    auto* a = instance->value;
    std::string description = instance->standalone ? "Compressed file" : archive_format_name(a) ? archive_format_name(a) : "Archive";
    description += instance->outerCompression;
    for (int i = 0; i < archive_filter_count(a); i++) {
        if (archive_filter_code(a, i) != ARCHIVE_FILTER_NONE && archive_filter_name(a, i))
            description += std::string(" · ") + archive_filter_name(a, i);
    }
    return utf8(env, description.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_aarchive_NativeArchive_free(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Reader*>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_aarchive_NativeArchive_version(JNIEnv* env, jclass) {
    return utf8(env, archive_version_string());
}
