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
            for (String suffix : new String[]{"gz", "bz2", "xz", "lzma", "zst", "lz4", "br", "Z", "lz", "lzo", "sz", "snappy", "zlib", "deflate", "uu"}) {
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

    private static byte[] readAll(NativeArchive archive) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8191];
        int count;
        while ((count = archive.read(buffer)) != 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
