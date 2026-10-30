# Nested compression stress fixture

`many-layers.bin` wraps one binary payload in **32 layers**: 20 compression or
encoding passes and 12 archive containers. The containers include eight TARs,
two ZIPs, one 7z and one CPIO. The encoders include gzip, bzip2, XZ, LZMA,
Zstandard, LZ4, lzip, LZOP, Brotli, zlib, raw DEFLATE, Unix compress, framed and
raw Snappy, and uuencode. ZIP and 7z also compress their contents internally.

The original `hello.txt` contains just `Hello World!\n` (13 bytes).
The wrapped fixture is about 3 KiB. Its payload SHA-256 is
`03ba204e50d126e4674c005e04d82e84c21366780af1f43bd54a37816b6ab340`.
The full layer order, intermediate names and sizes, and outer-file checksum are
in [many-layers.json](many-layers.json). Archive names are deliberately `.bin`;
compression suffixes inside containers provide hints for codecs without signatures.

Open this file in the app and use **Extract all**. It should show the innermost
filename and recover the original payload, without intermediate wrappers.
It exercises the existing 32-layer limit without raising it.

Regenerate using the independent Python/system encoders:

```sh
python3 tests/stress_fixture.py /tmp/aarchive-stress
```

This requires the same tools as the host tests. Metadata and payloads are fixed;
encoded bytes may differ between encoder versions. The fixture was generated
specifically for this project and contains no third-party content.

Both host and Android native suites test the checked-in file and a newly generated
copy, stream the extracted payload to disk, and verify its filename, size, exact
bytes, SHA-256 and end of archive. The existing excessive-nesting fixture separately
checks rejection beyond the limit.
