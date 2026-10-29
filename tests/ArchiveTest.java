package dev.aarchive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ArchiveTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejected(String path) throws Exception {
        try { ArchivePath.components(path); throw new AssertionError("Accepted unsafe path: " + path); }
        catch (IOException expected) { assertions++; }
    }
    private static void invalidPlan(ArchiveEntry... entries) throws Exception {
        Set<Integer> selected = new HashSet<>();
        for (int i = 0; i < entries.length; i++) selected.add(i);
        try { ArchivePlan.validate(Arrays.asList(entries), selected); throw new AssertionError("Accepted conflicting plan"); }
        catch (IOException expected) { assertions++; }
    }
    public static void main(String[] args) throws Exception {
        for (String path : new String[]{"../evil", "a/../../evil", "/etc/passwd", "C:/evil", "a\\..\\evil", "a\0b", ""}) rejected(path);
        check(Arrays.equals(ArchivePath.components("./a//b/"), new String[]{"a", "b"}), "Normalize relative paths");
        check(Arrays.equals(ArchivePath.components("日本語/hello 🌿.txt"), new String[]{"日本語", "hello 🌿.txt"}), "Unicode paths");
        invalidPlan(new ArchiveEntry("a", 1, 0100000, false), new ArchiveEntry("a/b", 1, 0100000, false));
        invalidPlan(new ArchiveEntry("./a", 1, 0100000, false), new ArchiveEntry("a", 1, 0100000, false));
        invalidPlan(new ArchiveEntry("link", 1, 0100000, true));
        invalidPlan(new ArchiveEntry("pipe", 1, 0010000, false));
        invalidPlan(new ArchiveEntry("./", 1, 0100000, false));

        Path fixtures = Paths.get(args[0]);
        for (String fixture : new String[]{"sample.zip", "sample.tar.gz", "sample.tar.xz", "sample.7z",
                "sample.tar.bz2", "sample.tar.zst", "sample.tar.lz4", "sample.tar.br", "sample.xar"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                int files = 0;
                ArchiveEntry entry;
                while ((entry = archive.next()) != null) {
                    if (entry.directory()) continue;
                    check(entry.path.equals("日本語/hello 🌿.txt"), fixture + " preserves UTF-8 name: " + entry.path);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[3];
                    int count;
                    while ((count = archive.read(buffer)) > 0) output.write(buffer, 0, count);
                    check(output.toString(StandardCharsets.UTF_8).equals("Hello from libarchive!\n"), fixture + " payload");
                    files++;
                }
                check(files == 1, fixture + " file count");
                check(!archive.format().isEmpty(), fixture + " detected format");
            }
        }
        byte[] expectedPayload = Files.readAllBytes(fixtures.resolve("report.csv"));
        for (String fixture : new String[]{"table.csv.gz", "table.csv.br", "table.csv.zst"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                check(archive.next().path.equals("table.csv"), "CSV is a standalone file, not an mtree manifest");
                check(new String(readAll(archive), StandardCharsets.UTF_8).equals("name,value\nalpha,42\nbeta,73\n"), fixture + " CSV payload");
            }
        }
        for (String suffix : new String[]{"gz", "bz2", "xz", "lzma", "zst", "lz4", "br", "Z", "lz", "lzo", "sz", "snappy", "zlib", "deflate", "uu"}) {
            String fixture = "report.csv." + suffix;
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "", fixture)) {
                ArchiveEntry entry = archive.next();
                check(entry != null && entry.path.equals("report.csv"), fixture + " output filename");
                check(entry.extractable() && !entry.directory(), fixture + " regular file");
                check(Arrays.equals(readAll(archive), expectedPayload), fixture + " decompressed payload");
                check(archive.next() == null, fixture + " exactly one entry");
                check(archive.format().contains("Compressed file"), fixture + " UI identifies a compressed file");
            }
        }
        for (String fixture : new String[]{"empty.gz", "empty.br", "empty.sz"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                check(archive.next() != null, fixture + " has an output entry");
                check(readAll(archive).length == 0, fixture + " empty output");
                check(archive.next() == null, fixture + " one entry");
            }
        }
        for (String fixture : new String[]{"concatenated.gz", "nested.gz.xz"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                check(archive.next() != null, fixture + " entry");
                check(Arrays.equals(readAll(archive), expectedPayload), fixture + " payload");
            }
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("outer.br.gz").toString(), "")) {
            check(archive.next().path.equals("outer.br"), "Only remove extensions for layers actually decoded");
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("opaque.bin").toString(), "")) {
            check(archive.next() != null && Arrays.equals(readAll(archive), expectedPayload), "Detect zlib without relying on an extension");
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("report.csv.gz").toString(), "", "日本語.csv.gz")) {
            check(archive.next().path.equals("日本語.csv"), "Use original document name instead of cached filename");
        }
        for (String fixture : new String[]{"truncated.gz", "truncated.bz2", "truncated.xz", "truncated.zst",
                "truncated.lz4", "truncated.br", "truncated.sz", "truncated.zlib", "truncated.deflate",
                "bad-checksum.gz", "bad-checksum.sz", "reserved.sz"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                archive.next();
                readAll(archive);
                throw new AssertionError("Damaged stream accepted: " + fixture);
            } catch (IOException expectedError) { assertions++; }
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("links.tar").toString(), "")) {
            check(archive.next().link, "Symbolic link identified");
            check(archive.next().link, "Hard link identified");
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("secret.zip").toString(), "open-sesame")) {
            check(archive.next() != null, "Encrypted entry");
            byte[] buffer = new byte[128];
            check(archive.read(buffer) > 0, "Password decrypts archive");
        }
        for (String fixture : new String[]{"aes128.zip", "aes256.zip", "aes256-stored.zip"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "password")) {
                check(archive.next().path.equals("README"), fixture + " AES encrypted entry");
                byte[] content = readAll(archive);
                check(content.length == 6818 && new String(content, StandardCharsets.UTF_8).contains("libarchive"), fixture + " decrypted and authenticated payload");
                check(archive.next() == null, fixture + " EOF");
            }
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "wrong")) {
                archive.next();
                readAll(archive);
                throw new AssertionError("Wrong AES password accepted: " + fixture);
            } catch (IOException expectedError) { assertions++; }
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("secret.zip").toString(), "wrong")) {
            archive.next();
            archive.read(new byte[128]);
            throw new AssertionError("Wrong password accepted");
        } catch (IOException expected) { assertions++; }
        try (NativeArchive ignored = new NativeArchive(fixtures.resolve("broken.zip").toString(), "")) {
            throw new AssertionError("Invalid archive accepted");
        } catch (IOException expected) { assertions++; }
        NativeArchive closed = new NativeArchive(fixtures.resolve("sample.zip").toString(), "");
        closed.close(); closed.close();
        try { closed.next(); throw new AssertionError("Closed reader accepted"); }
        catch (IOException expected) { assertions++; }
        System.out.println("Passed " + assertions + " assertions; " + NativeArchive.version());
    }

    private static byte[] readAll(NativeArchive archive) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8191];
        int count;
        while ((count = archive.read(buffer)) != 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
