# Third-party notices

The Android application bundles the following checksum-pinned native libraries.
Their copyright and license texts are reproduced in `app/src/main/assets/licenses/`.

The Java UI also bundles Material Components for Android 1.14.0 and its AndroidX
dependencies (including AppCompat, Lifecycle and Core), from the Android Open
Source Project, under Apache 2.0. See `material-LICENSE.txt` and
https://github.com/material-components/material-components-android/tree/1.14.0.
The back-arrow icon follows the Apache-licensed Material Icons design.

- libarchive: https://github.com/libarchive/libarchive/tree/v3.8.10 — primarily BSD
  licenses; see `libarchive-COPYING.txt` for the complete upstream notices.
  Local patches fix empty compressed output and stored-RAR CRC validation;
  see `app/src/main/cpp/cmake/Fix*.cmake`.
- XZ/liblzma: https://github.com/tukaani-project/xz/tree/v5.8.3 — see
  `xz-COPYING.txt` and the accompanying license texts. Only liblzma is linked;
  command-line tools are disabled.
- zlib: supplied by Android, https://zlib.net/zlib_license.html.
- bzip2 1.0.8: https://sourceware.org/bzip2/ — bzip2 license, `bzip2-LICENSE.txt`.
- Zstandard 1.5.7: https://github.com/facebook/zstd/tree/v1.5.7 — distributed under
  its BSD license, `zstd-LICENSE.txt`.
- LZ4 1.10.0: https://github.com/lz4/lz4/tree/v1.10.0 — the library's BSD license,
  `lz4-LICENSE.txt`; command-line tools are not bundled.
- Brotli 1.2.1: https://github.com/google/brotli/tree/v1.2.1 — MIT license,
  `brotli-LICENSE.txt`.
- Snappy 1.3.1: https://github.com/google/snappy/tree/1.3.1 — BSD license,
  `snappy-COPYING.txt`.
- Bzip3 1.5.5: https://github.com/iczelia/bzip3/tree/1.5.5 — LGPL 3.0 or later,
  `bzip3-LICENSE.txt` and `bzip3-GPL-3.0.txt`. Its bundled libsais implementation
  uses Apache 2.0, `bzip3-libsais-LICENSE.txt`. Linked into this GPLv3 application;
  upstream command-line tools are used only for tests.
- LZFSE, commit `e634ca58b4821d9f3d560cdc6df5dec02ffc93fd`:
  https://github.com/lzfse/lzfse — Apple's BSD 3-clause license,
  `lzfse-LICENSE.txt`. Includes the LZVN implementation used inside LZFSE streams.
- Expat 2.9.0: https://github.com/libexpat/libexpat/tree/R_2_9_0 — MIT license,
  `expat-COPYING.txt`; used by libarchive's XAR reader.
- Mbed TLS 3.6.7: https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.7 —
  distributed under Apache 2.0, `mbedtls-LICENSE.txt`; supplies archive digests and
  ZIP encryption primitives. Update checks use Android's HTTPS stack.
- LZOKay, commit `db2df1fcbebc2ed06c10f727f72567d40f06a2be`:
  https://github.com/AxioDL/lzokay — MIT license, `lzokay-LICENSE.txt`. AArchive's
  compatibility adapter exposes its LZO1X implementation to libarchive. GPL liblzo
  is not linked into the app. Tests use a separately installed system liblzo to
  generate independent LZOP fixtures.
- Gradle wrapper: https://github.com/gradle/gradle, Apache License 2.0.

No upstream endorsement is implied.
