#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
native_build_dir="${AARCHIVE_NATIVE_BUILD_DIR:-$project_dir/build/host-native}"
cmake -S "$project_dir/app/src/main/cpp" -B "$native_build_dir" -G Ninja -DAARCHIVE_HOST_TEST=ON
cmake --build "$native_build_dir" --target aarchive -j 4
mkdir -p "$test_dir/fixtures" "$test_dir/classes"
python3 "$project_dir/tests/make-fixtures.py" "$test_dir/fixtures"
javac -d "$test_dir/classes" \
    "$project_dir/app/src/main/java/dev/aarchive/ArchiveEntry.java" \
    "$project_dir/app/src/main/java/dev/aarchive/ArchivePath.java" \
    "$project_dir/app/src/main/java/dev/aarchive/ArchivePlan.java" \
    "$project_dir/app/src/main/java/dev/aarchive/NativeArchive.java" \
    "$project_dir/tests/ArchiveTest.java"
java -Djava.library.path="$native_build_dir" -cp "$test_dir/classes" dev.aarchive.ArchiveTest "$test_dir/fixtures"
