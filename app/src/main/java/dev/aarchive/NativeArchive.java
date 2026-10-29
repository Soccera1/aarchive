package dev.aarchive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Each reader is confined to one worker thread. */
public final class NativeArchive implements AutoCloseable {
    static { System.loadLibrary("aarchive"); }
    private long handle;

    public NativeArchive(String path, String password) throws IOException {
        this(path, password, new java.io.File(path).getName());
    }

    public NativeArchive(String path, String password, String displayName) throws IOException {
        handle = open(path.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8),
                displayName.getBytes(StandardCharsets.UTF_8));
    }

    public ArchiveEntry next() throws IOException { return next(handle); }
    public int read(byte[] buffer) throws IOException { return read(handle, buffer); }
    public String format() { return format(handle); }
    public static native String version();

    @Override public void close() {
        if (handle != 0) { free(handle); handle = 0; }
    }

    private static native long open(byte[] path, byte[] password, byte[] displayName) throws IOException;
    private static native ArchiveEntry next(long handle) throws IOException;
    private static native int read(long handle, byte[] buffer) throws IOException;
    private static native String format(long handle);
    private static native void free(long handle);
}
