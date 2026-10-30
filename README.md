# AArchive

An Android app for browsing and extracting archives and compressed files, built
with libarchive and additional native codecs. Requires Android 8.0 or newer;
supports ARM64 and x86-64.

[Screenshots](docs/screenshot.png) · [Standalone gzip](docs/standalone-gzip.png)

## Build

Use JDK 21 and install these components through Android Studio's SDK Manager:

| Component | Version |
| --- | --- |
| Android SDK Platform | 35 |
| Android SDK Build Tools | 35.0.0 |
| Android NDK | 28.0.13004108 |
| CMake | 3.22.1 |

Open the project in Android Studio, or set `JAVA_HOME` to your JDK and
`ANDROID_HOME` to your Android SDK, then run:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it
on a connected device with USB debugging enabled:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build needs internet access to download Gradle and checksum-pinned
native dependencies. The app itself does not require internet or broad storage
permissions.

## Usage

Choose **Open file**, then select an archive or compressed file through Android's
document picker. For archives, select entries to extract, or choose **Extract all**.
Search filters the list without changing the selection. Selecting a directory
creates that directory; select its file entries too to extract their contents.

A standalone file such as `report.csv.gz` shows `report.csv` and a **Decompress
file** action. Compressed archives such as `.tar.gz`, `.tar.zst`, and `.tar.br`
show their entries. Choose an output folder through Android's folder picker.

Use **Password** to retry an encrypted archive. Tap an operation's status message
to read its full details.

## Supported formats

Archives include ZIP, TAR, 7z, RAR/RAR5, CPIO, CAB, ISO, AR/DEB, RPM, XAR,
LHA/LZH, and WARC. Traditional and AES-128/AES-256 encrypted ZIP files are supported.

| Compression or encoding | Extensions |
| --- | --- |
| gzip, including concatenated members | `.gz`, `.gzip` |
| bzip2 | `.bz2`, `.bz` |
| XZ / LZMA | `.xz`, `.lzma` |
| Zstandard | `.zst`, `.zstd` |
| LZ4 framed / legacy | `.lz4` |
| lzip | `.lz` |
| LZOP / LZO1X | `.lzo` |
| Unix compress | `.Z` |
| Brotli | `.br` |
| Snappy standard framed / raw blocks | `.sz`, `.snappy` |
| zlib | `.zlib`, `.zz` |
| Raw DEFLATE | `.deflate` |
| uuencode | `.uu`, `.uue` |

Most formats are detected by content. Brotli and raw DEFLATE require their filename
extensions; raw Snappy requires `.snappy`. Output filenames retain any compression
suffixes for layers that have not been decoded.

Not supported: archive creation or editing, encrypted 7z, external dictionaries,
lrzip, grzip, bzip3, ZPAQ, LZFSE, WIM, and some container-specific compression
variants. Snappy support does not include Hadoop/Xerial framing or containers such
as Parquet. The app's **Formats** dialog also lists these limitations.

## Extraction limits

- Each extraction creates a new `AArchive-xxxxxxxx` folder. Existing destination
  files are not overwritten.
- Only regular files and directories are extracted. Links, special files, unsafe
  paths, duplicate paths, and file/directory conflicts are rejected.
- Imports and total extracted data are limited to 10 GiB, listings to 100,000
  entries, and paths to 64 components. Raw Snappy blocks are limited to 64 MiB.
- Input files are cached privately before opening. Allow space for both the input
  and extracted output. The current file and password are not restored after
  process termination.
- Cancellation takes effect between I/O calls. On failure or cancellation, the app
  attempts to remove its output folder and reports if partial output remains.

## Development checks

```sh
./gradlew assembleDebug lintDebug
./tests/run-host-tests.sh
```

Host tests require a JDK, CMake, Ninja, C/C++ compilers, zlib development headers,
Python 3.11+, `zip`, `bsdtar` or `7z`, `zstd`, `lz4`, `brotli`, `compress`
(ncompress), and system liblzo2. They build the same pinned native backends as the
app and cover formats, filenames, encrypted ZIP, malformed input, and path safety.
Build output is cached in `build/host-native`.

To run the native suite against the APK on a connected Android device or emulator:

```sh
./tests/run-android-native-tests.sh
```

This also requires `ANDROID_HOME`, `adb`, and `unzip`. Set `ANDROID_SERIAL` when
multiple devices are connected. Native tests do not exercise the GUI or document
providers; check picker access, extraction, cancellation, and rotation on-device.

## Dependencies and licenses

Native dependencies are pinned in
[CMakeLists.txt](app/src/main/cpp/CMakeLists.txt). Versions and license notices are
listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); license texts are also
bundled in the app's assets.
