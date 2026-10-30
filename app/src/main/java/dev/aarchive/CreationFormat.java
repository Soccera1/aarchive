package dev.aarchive;

/** The output combinations supported by the bundled native writers. */
public final class CreationFormat {
    private CreationFormat() {}

    public static final String[] IDS = {"zip", "7zip", "tar", "cpio", "raw"};
    public static final String[] LABELS = {"ZIP archive", "7z archive", "TAR archive", "CPIO archive", "Compressed file"};
    public static final String[] FILTERS = {"none", "gzip", "bzip2", "bzip3", "xz", "lzma", "zstd", "lz4", "lzfse", "lzip", "lzop", "compress", "uuencode", "brotli", "snappy", "zlib", "deflate"};

    public static String[] codecs(String format) {
        switch (format) {
            case "zip": return new String[]{"deflate", "store", "bzip2", "lzma", "xz", "zstd"};
            case "7zip": return new String[]{"lzma2", "lzma1", "store", "deflate", "bzip2", "ppmd", "zstd"};
            case "raw": return java.util.Arrays.copyOfRange(FILTERS, 1, FILTERS.length);
            case "tar": case "cpio": return FILTERS.clone();
            default: throw new IllegalArgumentException("Unsupported output format: " + format);
        }
    }

    public static String label(String codec) {
        switch (codec) {
            case "none": case "store": return "None (store)";
            case "gzip": return "Gzip";
            case "bzip2": return "Bzip2";
            case "bzip3": return "Bzip3";
            case "lzfse": return "LZFSE";
            case "xz": return "XZ";
            case "lzma": case "lzma1": return "LZMA";
            case "lzma2": return "LZMA2";
            case "zstd": return "Zstandard";
            case "lz4": return "LZ4";
            case "lzip": return "Lzip";
            case "lzop": return "LZOP";
            case "compress": return "Unix compress (.Z)";
            case "uuencode": return "Uuencode";
            case "brotli": return "Brotli";
            case "snappy": return "Snappy (framed)";
            case "zlib": return "Zlib";
            case "deflate": return "Deflate";
            case "ppmd": return "PPMd";
            default: throw new IllegalArgumentException("Unsupported codec: " + codec);
        }
    }

    public static boolean hasLevel(String codec) {
        return !codec.equals("none") && !codec.equals("store") && !codec.equals("compress")
                && !codec.equals("uuencode") && !codec.equals("lzop") && !codec.equals("snappy")
                && !codec.equals("bzip3") && !codec.equals("lzfse");
    }

    public static String suffix(String format, String codec) {
        if (format.equals("zip")) return ".zip";
        if (format.equals("7zip")) return ".7z";
        String base = format.equals("raw") ? "" : "." + format;
        switch (codec) {
            case "none": return base;
            case "gzip": return base + ".gz";
            case "bzip2": return base + ".bz2";
            case "bzip3": return base + ".bz3";
            case "lzfse": return base + ".lzfse";
            case "xz": return base + ".xz";
            case "lzma": return base + ".lzma";
            case "zstd": return base + ".zst";
            case "lz4": return base + ".lz4";
            case "lzip": return base + ".lz";
            case "lzop": return base + ".lzo";
            case "compress": return base + ".Z";
            case "uuencode": return base + ".uu";
            case "brotli": return base + ".br";
            case "snappy": return base + ".sz";
            case "zlib": return base + ".zlib";
            case "deflate": return base + ".deflate";
            default: throw new IllegalArgumentException("Unsupported codec: " + codec);
        }
    }

    public static String mime(String format, String codec) {
        if (format.equals("zip")) return "application/zip";
        if (format.equals("7zip")) return "application/x-7z-compressed";
        if (codec.equals("none")) return format.equals("tar") ? "application/x-tar" : "application/x-cpio";
        switch (codec) {
            case "gzip": return "application/gzip";
            case "bzip2": return "application/x-bzip2";
            case "xz": return "application/x-xz";
            case "zstd": return "application/zstd";
            default: return "application/octet-stream";
        }
    }
}
