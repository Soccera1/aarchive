#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
apk="${1:-$project_dir/app/build/outputs/apk/debug/app-debug.apk}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK containing platform 35 and Build Tools 35.0.0}"
test_dir="$(mktemp -d)"
device_dir="/data/local/tmp/aarchive-native-tests-$$"
cleanup() {
    adb shell rm -rf "$device_dir" >/dev/null 2>&1 || true
    rm -rf "$test_dir"
}
trap cleanup EXIT
mkdir -p "$test_dir/fixtures" "$test_dir/classes" "$test_dir/dex"
python3 "$project_dir/tests/make-fixtures.py" "$test_dir/fixtures"
javac --release 17 -d "$test_dir/classes" \
    "$project_dir/app/src/main/java/dev/aarchive/ArchiveEntry.java" \
    "$project_dir/app/src/main/java/dev/aarchive/ArchivePath.java" \
    "$project_dir/app/src/main/java/dev/aarchive/ArchivePlan.java" \
    "$project_dir/app/src/main/java/dev/aarchive/NativeArchive.java" \
    "$project_dir/app/src/main/java/dev/aarchive/NativeWriter.java" \
    "$project_dir/app/src/main/java/dev/aarchive/CreationFormat.java" \
    "$project_dir/tests/ArchiveTest.java" "$project_dir/tests/WriterTest.java"
jar --create --file "$test_dir/tests.jar" -C "$test_dir/classes" .
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 26 \
    --lib "$ANDROID_HOME/platforms/android-35/android.jar" \
    --output "$test_dir/dex" "$test_dir/tests.jar"
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
unzip -p "$apk" "lib/$abi/libaarchive.so" > "$test_dir/libaarchive.so"
adb shell mkdir -p "$device_dir"
adb push "$test_dir/dex/classes.dex" "$test_dir/libaarchive.so" "$test_dir/fixtures" "$device_dir/" >/dev/null
adb shell "CLASSPATH=$device_dir/classes.dex app_process -Djava.library.path=$device_dir /system/bin dev.aarchive.ArchiveTest $device_dir/fixtures"
adb shell "CLASSPATH=$device_dir/classes.dex app_process -Djava.library.path=$device_dir /system/bin dev.aarchive.WriterTest $device_dir/fixtures"
