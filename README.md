# AArchive

A native Android archive and compressed-file browser, using **libarchive** plus
dedicated native codecs for formats libarchive does not decode.

Open a file using Android's document picker, search its paths, select individual
entries, and extract them to a folder of your choice. With no selection, **Extract
all** extracts every regular file and directory. Selecting a directory creates that
directory; select its file entries too to extract their contents. Search filters the
list; it does not change the extraction selection.

Standalone compressed files work too: `report.csv.gz` shows one `report.csv`
entry and a **Decompress file** action. Compressed archives such as `.tar.gz`,
`.tar.zst`, and `.tar.br` show the archive's entries instead. The main screen
contains file controls and operation status, without promotional headings or slogans.

[View the Android screenshot](docs/screenshot.png) or the
[standalone gzip screen](docs/standalone-gzip.png).

The app uses Android's Storage Access Framework for local and cloud document
providers, requires no storage or internet permission, and keeps archive processing
off the UI thread. Progress, cancellation, and the loaded archive survive rotation.
Use **Password** to retry an encrypted archive. Tap the status message for full details.

## Build

Requirements: JDK 17–23 (JDK 21 recommended), Android SDK Platform 35, Build Tools
35.0.0, NDK 28.0.13004108, and CMake 3.22.1. Android Studio can install these SDK
components. The Gradle wrapper downloads Gradle 8.11.1.

Open this directory in Android Studio, or set `ANDROID_HOME` and run:

```sh
./gradlew assembleDebug
```

The installable APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`. Android 8.0 or newer is
required; arm64 and x86-64 are included. The build fetches checksum-pinned sources
for libarchive 3.8.10, XZ/liblzma 5.8.3, bzip2 1.0.8, Zstandard 1.5.7, LZ4 1.10.0,
Brotli 1.2.1, Snappy 1.3.1, LZOKay, Expat 2.9.0, and Mbed TLS 3.6.7, then links them statically. Android's zlib
provides DEFLATE/gzip decoding. Dependency downloads require network access at build time.

## Supported operations and limits

- Browsing and extraction of ZIP, TAR, 7z, RAR/RAR5, CPIO, CAB, ISO, AR/DEB, RPM,
  XAR, LHA/LZH, WARC, and other libarchive containers, subject to codec availability.
  Archive creation and editing are not implemented.
- Standalone compression support is listed below. Compression filters are also
  available inside supported containers, including bzip2/Zstd/LZMA-compressed ZIPs
  where libarchive supports those variants. No external helper programs are used.
- Traditional and AES-128/AES-256 password-protected ZIP are supported. Encrypted 7z, external
  dictionaries, and some container-specific compression variants are unsupported.
- This is broad format support, not a claim to support every open-source compression
  format. lrzip, grzip, bzip3, ZPAQ, LZFSE, WIM and application-specific containers
  still require additional engines. The app's **Formats** dialog exposes these limits.
- Each extraction creates a new `AArchive-xxxxxxxx` directory. Existing destination
  files are not overwritten. Symlinks, hard links, devices and other special files
  are blocked. Absolute paths, parent traversal, ambiguous duplicate paths, and
  file/directory conflicts are rejected before output is created.
- Imports and total extracted data are limited to 10 GiB; listings to 100,000
  entries; paths to 64 components. Archives are cached privately before opening,
  so free space must accommodate the imported archive and extracted output.
- On failure or cancellation, the app attempts to delete its new output directory.
  A provider may refuse deletion; the status reports when partial output remains.
  Cancellation is checked between I/O calls; a blocked provider or native decoder
  call must return before cancellation takes effect.
- The current archive and password are session state, not persisted across process
  death. Closing the activity deletes its cached archive; Android may retain cache
  files after an unexpected process termination.

| Compression | Common extensions | Backend |
| --- | --- | --- |
| gzip, including concatenated members | `.gz`, `.gzip` | zlib; header/trailer and CRC validation |
| bzip2 | `.bz2`, `.bz` | libarchive + bzip2 |
| XZ / LZMA | `.xz`, `.lzma` | libarchive + liblzma |
| Zstandard | `.zst`, `.zstd` | libarchive + Zstandard |
| LZ4 framed / legacy | `.lz4` | libarchive + LZ4 |
| lzip | `.lz` | libarchive + liblzma |
| LZOP / LZO1X | `.lzo` | libarchive + MIT-licensed LZOKay compatibility adapter |
| Unix compress | `.Z` | libarchive |
| Brotli | `.br` | Brotli streaming decoder |
| Snappy standard framed | `.sz`, framed `.snappy` | Snappy + framing parser with CRC32C validation |
| Snappy raw blocks | `.snappy` | Snappy; 64 MiB compressed/expanded block limit |
| zlib streams | `.zlib`, `.zz` | zlib; also detected by header |
| raw DEFLATE | `.deflate` | zlib |
| uuencode | `.uu`, `.uue` | libarchive |

Most formats are detected by content. Brotli and raw DEFLATE lack a reliable magic
signature and need their filename extension; unframed Snappy needs `.snappy`.
The original document name is retained even though the import is cached under a
temporary name. Gzip header filenames are not used as output paths. Output names
remove only suffixes corresponding to decoded layers. A remaining unsupported
inner compression layer keeps its extension and can be opened separately.
Snappy's standard framing and raw blocks are supported; Hadoop/Xerial framing,
Parquet, and other application-specific wrappers are not standalone Snappy streams.
Mtree manifest detection is enabled only for names ending in `.mtree` (optionally
compressed), `mtree`, or `METALOG`, so ordinary compressed CSV/text is not mistaken
for a metadata manifest.

## Verification

Host integration tests require a JDK, CMake, Ninja, C/C++ compilers, zlib development
headers, Python 3, `zip`, `bsdtar` (or `7z`), `zstd`, `lz4`, `brotli`, `compress`
(ncompress), and a system liblzo2 for independent LZOP fixture generation:

```sh
./tests/run-host-tests.sh
```

They compile the actual JNI bridge and the same pinned codec implementations as the
Android app. They check all listed compression types, ZIP/7z and compressed TAR
recognition, Unicode paths and original filenames, concatenated and nested streams,
empty files, truncation and checksum errors, encrypted ZIP, wrong passwords, invalid
archives, closed readers, and extraction-path validation. Host tests do not exercise
Android document providers or the GUI. Native build output is cached in `build/host-native`.

The identical suite can run against the library extracted from the APK on a
connected Android device or emulator (select one with `ANDROID_SERIAL` if needed):

```sh
./gradlew assembleDebug
./tests/run-android-native-tests.sh
```

Validated in this workspace: `assembleDebug` and `lintDebug` pass with no lint
findings. All 178 assertions pass both on the host and on Android 15 x86-64
using the actual APK's library, including XAR, compressed CSV detection, and
AES-128/AES-256 ZIP with password failures. APK signature and 16 KiB ZIP alignment
verification pass. An Android 15 x86-64 emulator smoke test
confirmed document-picker import, native ZIP listing, folder-provider extraction
with all four output file payloads verified against the source (including a Unicode
filename), search, selection, and selection/archive retention across rotation.
The updated UI was also checked for removal of promotional text, and a standalone
`report.csv.gz` was opened through the document picker and decompressed through the
folder picker with its output bytes verified against the original CSV.

Device smoke test: open each format, search and select entries, extract to a local
folder, verify bytes, rotate while importing/extracting, cancel a large operation,
and try traversal/link fixtures. Repeat with a cloud provider to check its write
and cleanup behavior. Run `./gradlew lintDebug` for Android static checks.

Third-party licenses are included in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
and the app's assets. Upstream references: [libarchive](https://github.com/libarchive/libarchive)
and [Android document storage](https://developer.android.com/training/data-storage/shared/documents-files).
