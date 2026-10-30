package dev.aarchive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class WriterTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private interface Action { void run() throws Exception; }
    private static void rejected(Action action, String description) throws Exception {
        try { action.run(); throw new AssertionError(description); }
        catch (IOException expected) { assertions++; }
    }
    private static final String FILE = "日本語/hello 🌿.txt";
    private static final byte[] DATA = payload();
    private static byte[] payload() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] line = "Archive creation payload.\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 6000; i++) data.write(line, 0, line.length);
        // Exercise both compressed and stored blocks, across encoder boundaries.
        int state = 12345;
        for (int i = 0; i < 131072; i++) {
            state ^= state << 13; state ^= state >>> 17; state ^= state << 5;
            data.write(state & 255);
        }
        return data.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        List<String> manifest = new ArrayList<>();
        for (String format : CreationFormat.IDS) {
            for (String codec : CreationFormat.codecs(format)) {
                String name = "created-" + format + "-" + codec + CreationFormat.suffix(format, codec);
                Path output = root.resolve(name);
                System.out.println("Create " + format + " / " + codec);
                try (NativeWriter writer = new NativeWriter(output.toString(), format, codec, 3)) {
                    if (!format.equals("raw")) {
                        writer.begin("日本語", 0, 1700000000000L, true); writer.endEntry();
                        writer.begin("empty-folder", 0, 1700000000000L, true); writer.endEntry();
                    }
                    writer.begin(format.equals("raw") ? "input.txt" : FILE, DATA.length, 1700000000000L, false);
                    for (int offset = 0; offset < DATA.length;) {
                        int length = Math.min(7919, DATA.length - offset);
                        byte[] part = Arrays.copyOfRange(DATA, offset, offset + length);
                        writer.write(part, part.length);
                        offset += length;
                    }
                    writer.endEntry();
                    if (!format.equals("raw")) { writer.begin("empty.txt", 0, 1700000000000L, false); writer.endEntry(); }
                    writer.finish();
                }
                try (NativeArchive archive = new NativeArchive(output.toString(), "", "output" + CreationFormat.suffix(format, codec))) {
                    int files = 0, directories = 0;
                    ArchiveEntry entry;
                    while ((entry = archive.next()) != null) {
                        if (entry.directory()) { directories++; continue; }
                        byte[] content = readAll(archive);
                        if (entry.path.equals("empty.txt")) check(content.length == 0, name + " empty file");
                        else {
                            check(format.equals("raw") || entry.path.equals(FILE), name + " Unicode filename: " + entry.path);
                            check(Arrays.equals(content, DATA), name + " payload");
                        }
                        files++;
                    }
                    check(files == (format.equals("raw") ? 1 : 2), name + " file count");
                    check(format.equals("raw") || directories == 2, name + " folder preservation");
                }
                manifest.add(name + "\t" + format + "\t" + codec);
            }
        }
        for (String codec : CreationFormat.codecs("raw")) {
            System.out.println("Create empty / " + codec);
            Path output = root.resolve("created-empty" + CreationFormat.suffix("raw", codec));
            try (NativeWriter writer = new NativeWriter(output.toString(), "raw", codec, 3)) {
                writer.begin("empty", 0, 0, false); writer.endEntry(); writer.finish();
            }
            try (NativeArchive archive = new NativeArchive(output.toString(), "", "empty" + CreationFormat.suffix("raw", codec))) {
                check(archive.next() != null && readAll(archive).length == 0, codec + " empty standalone file");
            }
        }
        Path output = root.resolve("writer-errors.zip");
        rejected(() -> { try (NativeWriter w = new NativeWriter(output.toString(), "rar", "none", 3)) {} }, "Unknown format accepted");
        rejected(() -> { try (NativeWriter w = new NativeWriter(output.toString(), "raw", "none", 3)) {} }, "Raw copy accepted");
        rejected(() -> { try (NativeWriter w = new NativeWriter(output.toString(), "zip", "deflate", 10)) {} }, "Invalid level accepted");
        try (NativeWriter writer = new NativeWriter(output.toString(), "zip", "deflate", 3)) {
            rejected(() -> writer.begin("../escape", 0, 0, false), "Unsafe writer path accepted");
            writer.begin("file", 2, 0, false);
            rejected(() -> writer.write(new byte[3], 3), "Entry overflow accepted");
            rejected(writer::endEntry, "Entry underflow accepted");
        }
        try (NativeWriter writer = new NativeWriter(output.toString(), "raw", "gzip", 3)) {
            writer.begin("one", 0, 0, false); writer.endEntry();
            rejected(() -> writer.begin("two", 0, 0, false), "Multiple raw entries accepted");
        }
        try (NativeWriter writer = new NativeWriter(output.toString(), "zip", "deflate", 3)) {
            writer.cancel();
            rejected(() -> writer.begin("file", 0, 0, false), "Cancelled writer accepted data");
        }
        NativeWriter closed = new NativeWriter(output.toString(), "tar", "none", 3);
        closed.close(); closed.close();
        rejected(closed::finish, "Closed writer accepted finish");
        Files.write(root.resolve("created-manifest.tsv"), manifest, StandardCharsets.UTF_8);
        System.out.println("Writer passed " + assertions + " assertions across " + manifest.size() + " format/codec combinations.");
    }

    private static byte[] readAll(NativeArchive archive) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] data = new byte[8191];
        int length;
        while ((length = archive.read(data)) != 0) output.write(data, 0, length);
        return output.toByteArray();
    }
}
