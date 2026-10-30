#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
tools_dir="${AARCHIVE_CODEC_TOOLS:-$project_dir/build/codec-tools}"
cmake -S "$project_dir/tests/codec-tools" -B "$tools_dir" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "$tools_dir" -j 4
