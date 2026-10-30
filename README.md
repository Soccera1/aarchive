# AArchive

An Android app for creating, browsing, and extracting archives and compressed files, built
with libarchive and additional native codecs. Requires Android 8.0 or newer;
supports ARMv7, ARMv8, x86, and x86-64 in one universal APK.

[Browse](docs/screenshot.png) · [Create](docs/create.png) · [Standalone gzip](docs/standalone-gzip.png)

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
native dependencies. Archive operations work offline and do not need broad storage
permissions. GitHub release builds use internet access for update checks.

## Rolling master release

Each successful push to `master` updates the
[master prerelease](https://github.com/Soccera1/aarchive/releases/tag/master),
after the APK build, lint, and native tests pass. It contains
`aarchive-master-universal.apk`, a SHA-256 checksum, and `update.json`. The rolling `master` tag
points to the tested commit. Pull requests and other branches do not publish.
Superseded builds are skipped so an older retry cannot replace a newer build.

| Architecture | Android ABI |
| --- | --- |
| x86 | `x86` |
| x86-64 | `x86_64` |
| ARMv7 | `armeabi-v7a` |
| ARMv8 | `arm64-v8a` |

Master APKs use the bundled [public development key](signing/README.md), with no
repository secrets required. They are testing builds with a stable signing identity
and increasing CI version codes, so subsequent master builds can update in place.
Uninstall APKs signed with a different key, including local debug builds, first.

To build the same signed variant locally:

```sh
./gradlew assembleMaster -PmasterBuildNumber=1
./scripts/verify-universal-apk.sh app/build/outputs/apk/master/app-master.apk
```

Use a build number at least as high as the installed master build when updating.

Numeric version tags such as `v1.2.3` build the signed `versioned` variant and
publish a regular GitHub release containing `aarchive-universal.apk`, its checksum,
and update metadata. Existing versioned releases are left unchanged on reruns.
Both release variants currently use the public development key and are testing
builds. The workflow run number supplies increasing Android version codes.

## In-app updates

On launch, GitHub release builds check for a newer APK and offer **Download and
install**. Downloading starts only after you accept. The APK's size, SHA-256,
package, version, channel, repository, and signing certificate are checked before
opening Android's installer. Android may ask you to enable **Allow from this
source**, and installation still requires your confirmation. Offline checks and
missing release metadata do not interrupt archive operations.

| Build provenance | Update source |
| --- | --- |
| Published master APK | Rolling `master` prerelease only |
| Published versioned APK | Latest numeric, non-prerelease version only |
| Local/debug/custom builds, PR and other branch artifacts | No GitHub checks or offers |

Provenance is embedded during the release build, independently of the signing key
or build-type name. Local `assembleMaster` and `assembleVersioned` builds default
to no update channel. The publishing workflow explicitly supplies
`-PupdateChannel=github-master` or `-PupdateChannel=github-release`, plus
`-PupdateRepository=OWNER/REPO`. Other distributors should leave the channel unset.
Checks use that repository's public GitHub API without credentials. Repositories
with older releases start offering updates once a release includes `update.json`.

## Usage

Choose **Open file**, then select an archive or compressed file through Android's
document picker. For archives, select entries to extract, or choose **Extract all**.
Search filters the list without changing the selection. Selecting a directory
creates that directory; select its file entries too to extract their contents.

A standalone file such as `report.csv.gz` shows `report.csv` and a **Decompress
file** action. Compressed archives such as `.tar.gz`, `.tar.zst`, and `.tar.br`
show their entries. Choose an output folder through Android's folder picker.

Externally compressed ZIP and 7z files, such as `foo.7z.gz` and `bar.zip.xz`,
also show their inner entries directly. Stored and internally compressed entries
are supported, along with password-protected ZIPs. This works with all supported
outer compression codecs. After each layer, supported codecs and archive formats
are detected again from the decoded bytes. Archives containing exactly one regular
file (plus optional directories) are automatically unwrapped when that file is
another recognized archive or compressed stream. This repeats for mixed chains;
multi-file archives and ordinary single-file archives keep their entries visible.
Brotli, raw DEFLATE, and raw Snappy still need their filename hints because they
have no reliable signature. These redundant combinations are read-only; they are
not offered in **Create**.

Use **Password** to retry an encrypted archive. Tap an operation's status message
to read its full details.

Choose **Create** to add files or folders, select an output format and compression,
then choose where to save the result. Folders include their contents and empty
subfolders. **Compressed file** compresses one file without an archive container;
choose TAR or CPIO with compression for multiple files, such as `.tar.gz` or
`.cpio.zst`. Compression levels are available where the encoder supports them.

| Creation format | Compression choices |
| --- | --- |
| ZIP | Store, Deflate, Bzip2, LZMA, XZ, Zstandard |
| 7z | Store, LZMA2, LZMA, Deflate, Bzip2, PPMd, Zstandard |
| TAR / CPIO | Uncompressed, or any standalone codec below |
| Standalone file | Gzip, Bzip2, XZ, LZMA, Zstandard, LZ4, Lzip, LZOP, Unix compress, Brotli, framed Snappy, Zlib, raw Deflate, Uuencode |

ZIP with Deflate and 7z with LZMA2 work with the widest range of readers. Newer
ZIP and 7z compression methods may require recent extraction software. Creation
does not support encryption or changing an existing archive.

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

Not supported: editing existing archives, encrypted 7z, external dictionaries,
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
- Decoded layers and single-file archive candidates use additional private cache
  for detection and seeking. Opening a file is limited to 32 nested layers and
  10 GiB of cumulative expanded cache data. Temporary data is removed when the
  reader closes, fails, or is cancelled. Allow space for this cache as well.
- Cancellation takes effect between I/O calls. On failure or cancellation, the app
  attempts to remove its output folder and reports if partial output remains.

Creation also limits input and output to 10 GiB, with 100,000 entries and 64 path
components. CPIO's per-file limit is smaller than 4 GiB. Sources are read through
Android's document picker. The app stages the output and one input file at a time
in private cache; 7z may need additional temporary storage. Allow free space for
these files and the saved result. A new output document is created, and the app
attempts to remove it on failure or cancellation. Source selection survives
rotation, but not process termination.

## Development checks

The checked-in [32-layer stress archive](tests/fixtures/many-layers.bin) combines
20 compression/encoding passes with 12 containers, including eight TARs. Both test
suites extract it and a newly generated copy, comparing the complete output and
SHA-256 with the original payload. See its [layer manifest and regeneration notes](tests/fixtures/many-layers.md).

```sh
./gradlew assembleDebug lintDebug
./tests/run-host-tests.sh
./tests/run-update-policy-tests.sh
python3 tests/test-master-release.py
```

Host tests require a JDK, CMake, Ninja, C/C++ compilers, zlib development headers,
Python 3.11+, `zip`, `bsdtar`, `zstd`, `lz4`, `brotli`, `compress`
(ncompress), and system liblzo2. They build the same pinned native backends as the
app and cover reading and creation, filenames, empty files and folders, encrypted
ZIP, malformed input, cancellation, and path safety.
Build output is cached in `build/host-native`. Independent decoders also check
the created output; recent ZIP/7z methods require bsdtar 3.8+ for those extra checks.

To run the native suite against the APK on a connected Android device or emulator:

```sh
./tests/run-android-native-tests.sh
```

Pass an APK path as the first argument to test a different variant, such as
`app/build/outputs/apk/master/app-master.apk`.

This also requires `ANDROID_HOME`, `adb`, and `unzip`. Set `ANDROID_SERIAL` when
multiple devices are connected. Native tests do not exercise the GUI or document
providers; check picker access, extraction, cancellation, and rotation on-device.

Updater tests with real signed APKs, mocked release responses, and Android's APK
parser run on a disposable emulator:

```sh
ANDROID_SERIAL=emulator-5554 ./tests/run-android-update-tests.sh
```

This script replaces `dev.aarchive` and its test package on the selected emulator.
It builds versions 7 and 8, tests both channel policies, corrupt/truncated downloads,
different signing keys, APK metadata, and read-only installer access. It refuses
physical devices. Afterward, this starts a mocked update offer for manually
checking the permission and installation screens on that emulator:

```sh
adb -s emulator-5554 shell am instrument -w -e ui true dev.aarchive.test/dev.aarchive.UpdateInstrumentation
```

Completing that manual installation replaces the instrumented app, so Android
ends the instrumentation session. Confirm the installed version afterward.

## Dependencies and licenses

Native dependencies are pinned in
[CMakeLists.txt](app/src/main/cpp/CMakeLists.txt). Versions and license notices are
listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); license texts are also
bundled in the app's assets.
