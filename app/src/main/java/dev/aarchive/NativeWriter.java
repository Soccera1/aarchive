package dev.aarchive;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Thread-confined writer. Call finish explicitly; close without finish aborts. */
public final class NativeWriter implements AutoCloseable {
    static { System.loadLibrary("aarchive"); }
    private long handle;
    private volatile boolean cancelled;

    public NativeWriter(String output, String format, String codec, int level) throws IOException {
        File target = new File(output).getAbsoluteFile();
        handle = open(utf8(target.getPath()), utf8(target.getParent()), utf8(format), utf8(codec), level, this);
    }

    public void begin(String path, long size, long modifiedMillis, boolean directory) throws IOException {
        ArchivePath.components(path);
        begin(handle, utf8(path), size, modifiedMillis, directory);
    }
    public void write(byte[] buffer, int length) throws IOException { write(handle, buffer, length); }
    public void endEntry() throws IOException { endEntry(handle); }
    public void finish() throws IOException { finish(handle); }
    public void cancel() { cancelled = true; }
    @Override public void close() {
        if (handle != 0) { free(handle); handle = 0; }
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static native long open(byte[] path, byte[] temporaryDirectory, byte[] format, byte[] codec, int level, NativeWriter owner) throws IOException;
    private static native void begin(long handle, byte[] path, long size, long modifiedMillis, boolean directory) throws IOException;
    private static native void write(long handle, byte[] data, int length) throws IOException;
    private static native void endEntry(long handle) throws IOException;
    private static native void finish(long handle) throws IOException;
    private static native void free(long handle);
}
