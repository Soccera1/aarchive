package dev.aarchive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
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
        testRar(fixtures);
        byte[] stressPayload = Files.readAllBytes(fixtures.resolve("stress-payload.bin"));
        byte[] stressDigest = MessageDigest.getInstance("SHA-256").digest(stressPayload);
        StringBuilder hash = new StringBuilder();
        for (byte value : stressDigest) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        check(Arrays.equals(stressPayload, "Hello World!\n".getBytes(StandardCharsets.UTF_8)), "Stress payload is Hello World");
        check(hash.toString().equals("03ba204e50d126e4674c005e04d82e84c21366780af1f43bd54a37816b6ab340"), "Stress payload has the fixed reference SHA-256");
        for (String fixture : new String[]{"many-layers.bin", "committed-many-layers.bin"}) {
            Path extracted = fixtures.resolve(fixture + ".extracted");
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                ArchiveEntry entry = archive.next();
                check(entry != null && entry.path.equals("hello.txt"), fixture + " reaches hello.txt through all 32 layers");
                check(entry.extractable() && !entry.directory(), fixture + " is a regular extractable file");
                check(entry.size == stressPayload.length, fixture + " original size");
                try (OutputStream output = Files.newOutputStream(extracted)) {
                    byte[] buffer = new byte[4093];
                    int length;
                    while ((length = archive.read(buffer)) != 0) output.write(buffer, 0, length);
                }
                byte[] actual = Files.readAllBytes(extracted);
                check(Arrays.equals(actual, stressPayload), fixture + " extracts every original byte correctly to disk");
                check(Arrays.equals(MessageDigest.getInstance("SHA-256").digest(actual), stressDigest), fixture + " extracted SHA-256");
                check(archive.next() == null, fixture + " has no leftover wrapper entries");
            } finally { Files.deleteIfExists(extracted); }
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("chain.bin").toString(), "")) {
            check(archive.next().path.equals("日本語/hello 🌿.txt"), "Mixed container/compression chain reaches innermost archive by magic");
            check(new String(readAll(archive), StandardCharsets.UTF_8).equals("Hello from libarchive!\n"), "Deep mixed chain payload");
            check(archive.next() == null, "Deep mixed chain has one final entry");
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("standalone-chain.bin").toString(), "")) {
            check(archive.next().path.equals("table.csv"), "One-file TAR unwraps standalone XZ");
            check(new String(readAll(archive), StandardCharsets.UTF_8).equals("name,value\nalpha,42\nbeta,73\n"), "Nested standalone payload");
            check(archive.format().startsWith("Compressed file"), "Nested standalone UI action");
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("multiple-nested.zip").toString(), "")) {
            check(archive.next().path.equals("inner.zip") && archive.next().path.equals("readme.txt"), "Multi-file archives stay visible");
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("unsafe-nested.tar").toString(), "")) {
            check(archive.next().path.equals("../inner.zip"), "Unwrapping cannot bypass unsafe archive paths");
        }
        try (NativeArchive ignored = new NativeArchive(fixtures.resolve("too-deep.bin").toString(), "")) {
            throw new AssertionError("Excessive archive nesting accepted");
        } catch (IOException expected) { check(expected.getMessage().contains("32 layers"), "Bound recursive archive nesting"); }
        for (String fixture : new String[]{"seek.zip.gz", "seek.7z.gz"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                check(archive.next().path.equals("seek-data.bin"), fixture + " seekable first entry");
                check(Arrays.equals(readAll(archive), Files.readAllBytes(fixtures.resolve("seek-data.bin"))), fixture + " large stored payload");
                check(archive.next() != null, fixture + " later entry");
                byte[] tail = fixture.startsWith("seek.zip") ? "Hello from libarchive!\n".getBytes(StandardCharsets.UTF_8)
                        : Files.readAllBytes(fixtures.resolve("chain-input.bin"));
                check(Arrays.equals(readAll(archive), tail), fixture + " later entry payload");
                check(archive.next() == null, fixture + " final EOF");
            }
        }
        for (String container : new String[]{"sample.zip", "stored.zip", "sample.7z", "stored.7z"}) {
            for (String suffix : new String[]{"gz", "bz2", "xz", "lzma", "zst", "lz4", "br", "Z", "lz", "lzo", "sz", "snappy", "zlib", "deflate", "uu", "bz3", "lzfse", "xerial.snappy"}) {
                String fixture = container + "." + suffix;
                try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                    ArchiveEntry entry = archive.next();
                    check(entry != null && entry.path.equals("日本語/hello 🌿.txt"), fixture + " opens the inner archive");
                    check(new String(readAll(archive), StandardCharsets.UTF_8).equals("Hello from libarchive!\n"), fixture + " extracts inner payload");
                    check(archive.next() == null, fixture + " one entry");
                    check(!archive.format().startsWith("Compressed file") && archive.format().contains(" · "), fixture + " archive and outer codec description: " + archive.format());
                }
            }
        }
        for (String fixture : new String[]{"sample.7z.gz.xz", "sample.zip.gz.br"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                check(archive.next().path.equals("日本語/hello 🌿.txt"), fixture + " nested compression entry");
                check(new String(readAll(archive), StandardCharsets.UTF_8).equals("Hello from libarchive!\n"), fixture + " nested compression payload");
            }
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("empty.zip.gz").toString(), "")) {
            check(archive.next() == null && !archive.format().startsWith("Compressed file"), "Wrapped empty ZIP remains an empty archive");
        }
        NativeArchive.Cancellation cancellation = new NativeArchive.Cancellation();
        cancellation.cancel();
        try (NativeArchive ignored = new NativeArchive(fixtures.resolve("sample.7z.gz").toString(), "", "sample.7z.gz", cancellation)) {
            throw new AssertionError("Cancelled wrapped archive was opened");
        } catch (IOException expected) { check(expected.getMessage().contains("cancelled"), "Cancel wrapped archive preparation"); }
        Path cached = fixtures.resolve("cached-wrapper.bin");
        Files.copy(fixtures.resolve("sample.zip.br"), cached, StandardCopyOption.REPLACE_EXISTING);
        try (NativeArchive archive = new NativeArchive(cached.toString(), "", "original.zip.br")) {
            check(archive.next().path.equals("日本語/hello 🌿.txt"), "Wrapped archive uses original document filename");
            check(new String(readAll(archive), StandardCharsets.UTF_8).equals("Hello from libarchive!\n"), "Cached wrapped archive payload");
        }
        for (String fixture : new String[]{"bad-wrapper.7z.gz", "truncated-wrapper.zip.xz"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                archive.next(); readAll(archive);
                throw new AssertionError("Damaged outer compression accepted: " + fixture);
            } catch (IOException expected) { assertions++; }
        }
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
        for (String suffix : new String[]{"gz", "bz2", "xz", "lzma", "zst", "lz4", "br", "Z", "lz", "lzo", "sz", "snappy", "zlib", "deflate", "uu", "bz3", "lzfse", "xerial.snappy"}) {
            String fixture = "report.csv." + suffix;
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "", fixture.replace(".xerial.snappy", ".snappy"))) {
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
        byte[] largePayload = Files.readAllBytes(fixtures.resolve("extra-large-payload.bin"));
        for (String suffix : new String[]{"bz3", "lzfse", "xerial.snappy"}) {
            for (String fixture : new String[]{"extra-magic-" + suffix + ".bin", "extra-large." + suffix, "empty." + suffix}) {
                try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                    check(archive.next() != null, fixture + " entry detected by signature");
                    byte[] expected = fixture.startsWith("extra-large") ? largePayload
                            : fixture.startsWith("empty") ? new byte[0] : expectedPayload;
                    check(Arrays.equals(readAll(archive), expected), fixture + " complete streaming payload");
                    check(archive.next() == null, fixture + " EOF");
                }
            }
        }
        for (String fixture : new String[]{"stored.lzfse", "small.lzfse", "extra-chain.bin", "default-block.bz3"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                check(archive.next() != null, fixture + " entry");
                byte[] expected = fixture.startsWith("stored") || fixture.startsWith("default-block") ? expectedPayload
                        : fixture.startsWith("small") ? String.join("", Collections.nCopies(30, "Hello World!\n")).getBytes(StandardCharsets.UTF_8)
                        : "Hello from libarchive!\n".getBytes(StandardCharsets.UTF_8);
                check(Arrays.equals(readAll(archive), expected), fixture + " decodes variant or nested chain");
                check(archive.next() == null, fixture + " EOF");
            }
        }
        for (String fixture : new String[]{"concatenated.gz", "nested.gz.xz"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                check(archive.next() != null, fixture + " entry");
                check(Arrays.equals(readAll(archive), expectedPayload), fixture + " payload");
            }
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("outer.br.gz").toString(), "")) {
            check(archive.next().path.equals("outer"), "Recursively decode inner Brotli after gzip");
            check(Arrays.equals(readAll(archive), expectedPayload), "Nested extra-codec payload");
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("opaque.bin").toString(), "")) {
            check(archive.next() != null && Arrays.equals(readAll(archive), expectedPayload), "Detect zlib without relying on an extension");
        }
        try (NativeArchive archive = new NativeArchive(fixtures.resolve("report.csv.gz").toString(), "", "日本語.csv.gz")) {
            check(archive.next().path.equals("日本語.csv"), "Use original document name instead of cached filename");
        }
        for (String fixture : new String[]{"truncated.gz", "truncated.bz2", "truncated.xz", "truncated.zst",
                "truncated.lz4", "truncated.br", "truncated.sz", "truncated.zlib", "truncated.deflate",
                "bad-checksum.gz", "bad-checksum.sz", "reserved.sz", "truncated.bz3", "trailing.bz3",
                "bad-block.bz3", "short-block.bz3", "bad-checksum.bz3", "oversized.bz3", "truncated.lzfse", "trailing.lzfse",
                "bad-header.lzfse", "truncated.xerial.snappy", "trailing.xerial.snappy", "bad-version.snappy", "oversized.snappy"}) {
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
        for (String fixture : new String[]{"secret.zip", "secret.zip.gz"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "open-sesame")) {
                check(archive.next() != null, fixture + " encrypted entry");
                byte[] buffer = new byte[128];
                check(archive.read(buffer) > 0, fixture + " password decrypts archive");
            }
        }
        for (String fixture : new String[]{"aes128.zip", "aes256.zip", "aes256-stored.zip", "aes128.zip.gz", "aes256.zip.gz", "aes256-stored.zip.gz"}) {
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

    private static void testRar(Path fixtures) throws Exception {
        Map<String, byte[]> stored = new LinkedHashMap<>();
        byte[] text = "test text document\r\n".getBytes(StandardCharsets.UTF_8);
        stored.put("test.txt", text);
        stored.put("testdir/test.txt", text);
        for (String suffix : new String[]{"", ".gz", ".xz", ".bz3", ".lzfse"}) {
            verifyRar(fixtures, "rar4-stored.rar" + suffix, stored, 2, 1);
            for (String level : new String[]{"normal", "best"}) {
                String name = "rar4-" + level + ".rar" + suffix;
                try (NativeArchive archive = new NativeArchive(fixtures.resolve(name).toString(), "")) {
                    check(archive.format().contains("RAR"), name + " detected as RAR");
                    int html = 0, small = 0, dirs = 0, links = 0;
                    ArchiveEntry entry;
                    while ((entry = archive.next()) != null) {
                        if (entry.link) { check(!entry.extractable(), name + " blocks link extraction"); links++; continue; }
                        if (entry.directory()) { dirs++; continue; }
                        byte[] data = readAll(archive);
                        check(entry.extractable() && data.length == entry.size, name + " regular file size");
                        if (entry.path.equals("testdir/test.txt")) {
                            check(Arrays.equals(data, text), name + " text payload");
                            small++;
                        } else {
                            check(entry.path.equals("LibarchiveAddingTest.html") || entry.path.equals("testdir/LibarchiveAddingTest.html"), name + " HTML path");
                            check(data.length == 20111, name + " complete HTML payload");
                            check(hexDigest(data).equals("ee16390e87152d7dec632ae2b37566da6135966e46b5166e6f40a1c827fa61ad"), name + " HTML SHA-256");
                            html++;
                        }
                    }
                    check(html == 2 && small == 1 && dirs == 2 && links == 1, name + " all entries preserved");
                }
            }
            Map<String, byte[]> single = new LinkedHashMap<>();
            single.put("helloworld.txt", "hello libarchive test suite!\n".getBytes(StandardCharsets.UTF_8));
            verifyRar(fixtures, "rar5-stored.rar" + suffix, single, 0, 0);
            single.clear();
            single.put("test.bin", rar5Payload(0, 1200));
            verifyRar(fixtures, "rar5-compressed.rar" + suffix, single, 0, 0);
            Map<String, byte[]> solid = new LinkedHashMap<>();
            for (int i = 1; i <= 4; i++) solid.put("test" + i + ".bin", rar5Payload(i, 4096));
            verifyRar(fixtures, "rar5-solid.rar" + suffix, solid, 0, 0);
            // Listing and extracting a selection skip earlier entries. A solid
            // decoder must still carry their dictionary forward correctly.
            try (NativeArchive archive = new NativeArchive(fixtures.resolve("rar5-solid.rar" + suffix).toString(), "")) {
                for (int i = 1; i <= 3; i++) check(archive.next().path.equals("test" + i + ".bin"), "Skip solid RAR entry " + i);
                check(archive.next().path.equals("test4.bin"), "Select last solid RAR entry");
                check(Arrays.equals(readAll(archive), solid.get("test4.bin")), "Extract selected solid RAR entry after skips");
                check(archive.next() == null, "Solid selection reaches EOF");
            }
        }
        Map<String, byte[]> nested = new LinkedHashMap<>();
        for (int i = 1; i <= 4; i++) nested.put("test" + i + ".bin", rar5Payload(i, 4096));
        verifyRar(fixtures, "rar-nested.bin", nested, 0, 0);
        for (String fixture : new String[]{"rar4-encrypted.rar", "rar5-encrypted.rar"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "password")) {
                while (archive.next() != null) readAll(archive);
                throw new AssertionError("Unsupported encrypted RAR was silently accepted: " + fixture);
            } catch (IOException expected) {
                check(expected.getMessage().toLowerCase(Locale.ROOT).contains("encrypt"), fixture + " explains unsupported encryption");
            }
        }
        for (String fixture : new String[]{"rar4-bad-crc.rar", "rar5-bad-crc.rar", "rar4-truncated.rar", "rar5-truncated.rar"}) {
            try (NativeArchive archive = new NativeArchive(fixtures.resolve(fixture).toString(), "")) {
                while (archive.next() != null) readAll(archive);
                throw new AssertionError("Damaged RAR was accepted: " + fixture);
            } catch (IOException expected) { assertions++; }
        }
    }

    private static void verifyRar(Path fixtures, String name, Map<String, byte[]> expected, int directories, int links) throws Exception {
        try (NativeArchive archive = new NativeArchive(fixtures.resolve(name).toString(), "")) {
            check(archive.format().contains("RAR"), name + " detected as RAR");
            Set<String> seen = new HashSet<>();
            int dirs = 0, linkCount = 0;
            ArchiveEntry entry;
            while ((entry = archive.next()) != null) {
                if (entry.link) { check(!entry.extractable(), name + " blocks link extraction"); linkCount++; continue; }
                if (entry.directory()) { dirs++; continue; }
                check(entry.extractable() && expected.containsKey(entry.path) && seen.add(entry.path), name + " regular file path");
                check(entry.size == expected.get(entry.path).length, name + " declared size");
                check(Arrays.equals(readAll(archive), expected.get(entry.path)), name + " full payload");
            }
            check(seen.equals(expected.keySet()) && dirs == directories && linkCount == links, name + " complete entry set");
        }
    }

    private static byte[] rar5Payload(int magic, int size) {
        byte[] result = new byte[size];
        for (int i = 0; i < size / 4; i++) {
            int k = i + 1;
            int value = Math.max(0, k * k - 3 * k + 1 + magic);
            for (int b = 0; b < 4; b++) result[i * 4 + b] = (byte)(value >>> (8 * b));
        }
        return result;
    }

    private static String hexDigest(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static byte[] readAll(NativeArchive archive) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8191];
        int count;
        while ((count = archive.read(buffer)) != 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
