#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
apk="${1:?Usage: verify-universal-apk.sh path/to/app.apk}"
build_tools="$ANDROID_HOME/build-tools/35.0.0"

"$build_tools/apksigner" verify --verbose "$apk"
"$build_tools/zipalign" -c -P 16 4 "$apk"

python3 - "$apk" <<'PY'
import struct
import sys
import zipfile

expected = {
    "armeabi-v7a": (1, 40),
    "arm64-v8a": (2, 183),
    "x86": (1, 3),
    "x86_64": (2, 62),
}
with zipfile.ZipFile(sys.argv[1]) as apk:
    actual = {name.split('/')[1] for name in apk.namelist()
              if name.startswith('lib/') and name.endswith('.so')}
    if actual != expected.keys():
        raise SystemExit(f"Expected all four Android ABIs, found {sorted(actual)}")
    for abi, (elf_class, machine) in expected.items():
        with apk.open(f"lib/{abi}/libaarchive.so") as library:
            header = library.read(20)
        if (len(header) < 20 or header[:4] != b'\x7fELF' or header[4] != elf_class
                or header[5] != 1 or struct.unpack_from('<H', header, 18)[0] != machine):
            raise SystemExit(f"Incorrect native library architecture for {abi}")
print("Verified signed universal APK: " + ", ".join(expected))
PY
