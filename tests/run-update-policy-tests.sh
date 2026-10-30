#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
javac -d "$test_dir" "$project_dir/app/src/main/java/dev/aarchive/UpdatePolicy.java" "$project_dir/tests/UpdatePolicyTest.java"
java -cp "$test_dir" dev.aarchive.UpdatePolicyTest
