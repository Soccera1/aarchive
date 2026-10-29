#include <jni.h>
#include <archive.h>
#include <archive_entry.h>
#include "stream_source.hpp"
#include <cerrno>
#include <algorithm>
#include <cctype>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Reader {
    archive* value = archive_read_new();
    std::unique_ptr<StreamSource> source;
    std::string name;
    archive_entry* pending = nullptr;
    bool first = true;
    bool standalone = false;
    bool empty = false;
    ~Reader() { if (value) archive_read_free(value); }
};

la_ssize_t sourceRead(archive* value, void* context, const void** data) {
    try { return static_cast<Reader*>(context)->source->read(data); }
    catch (const std::exception& error) {
        archive_set_error(value, EIO, "%s", error.what());
        return -1;
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
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_aarchive_NativeArchive_open(JNIEnv* env, jclass, jbyteArray path, jbyteArray password, jbyteArray displayName) {
    try {
        auto instance = std::make_unique<Reader>();
        auto* a = instance->value;
        if (!a) throw std::runtime_error("Could not allocate archive reader");
        instance->source = openExtraCodec(bytes(env, path), bytes(env, displayName));
        // Explicit native filters only: never register external helper programs.
        for (auto support : {archive_read_support_filter_none, archive_read_support_filter_gzip,
                archive_read_support_filter_bzip2, archive_read_support_filter_xz,
                archive_read_support_filter_lzma, archive_read_support_filter_zstd,
                archive_read_support_filter_lz4, archive_read_support_filter_lzip,
                archive_read_support_filter_lzop, archive_read_support_filter_compress,
                archive_read_support_filter_uu, archive_read_support_filter_rpm}) {
            if (support(a) != ARCHIVE_OK) throw std::runtime_error("A required native compression decoder is unavailable.");
        }
        for (auto support : {archive_read_support_format_ar, archive_read_support_format_cpio,
                archive_read_support_format_empty, archive_read_support_format_lha,
                archive_read_support_format_tar, archive_read_support_format_xar,
                archive_read_support_format_warc, archive_read_support_format_7zip,
                archive_read_support_format_cab, archive_read_support_format_rar,
                archive_read_support_format_rar5, archive_read_support_format_iso9660,
                archive_read_support_format_zip}) {
            if (support(a) != ARCHIVE_OK) throw std::runtime_error("A required archive reader is unavailable.");
        }
        // Mtree's heuristic also matches ordinary CSV/text. Only enable this
        // metadata-manifest format when the user opens a file named for it.
        auto hint = standaloneName(bytes(env, displayName), 64);
        std::transform(hint.begin(), hint.end(), hint.begin(), [](unsigned char c) { return std::tolower(c); });
        if (hint == "mtree" || hint == "metalog" || (hint.size() >= 6 && hint.compare(hint.size() - 6, 6, ".mtree") == 0))
            archive_read_support_format_mtree(a);
        archive_read_support_format_raw(a);
        const auto secret = bytes(env, password);
        if (!secret.empty()) archive_read_add_passphrase(a, secret.c_str());
        const int opened = instance->source
            ? archive_read_open(a, instance.get(), nullptr, sourceRead, nullptr)
            : archive_read_open_filename(a, bytes(env, path).c_str(), 65536);
        if (opened != ARCHIVE_OK) throw std::runtime_error(archive_error_string(a) ? archive_error_string(a) : "Could not open archive.");
        const int result = archive_read_next_header(a, &instance->pending);
        if (result != ARCHIVE_OK && result != ARCHIVE_EOF)
            throw std::runtime_error(archive_error_string(a) ? archive_error_string(a) : "Could not read archive header.");
        const bool compressed = instance->source || archive_filter_code(a, 0) != ARCHIVE_FILTER_NONE;
        size_t layers = instance->source ? 1 : 0;
        for (int i = 0; i < archive_filter_count(a); i++)
            if (archive_filter_code(a, i) != ARCHIVE_FILTER_NONE) layers++;
        instance->name = standaloneName(bytes(env, displayName), layers);
        instance->standalone = archive_format(a) == ARCHIVE_FORMAT_RAW
            || (archive_format(a) == ARCHIVE_FORMAT_EMPTY && compressed);
        instance->empty = result == ARCHIVE_EOF;
        if (instance->standalone && !compressed)
            throw std::runtime_error("Unrecognized archive or compressed stream.");
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
    if (instance->source) description += std::string(" · ") + instance->source->codec();
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
