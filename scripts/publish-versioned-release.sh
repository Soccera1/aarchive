#!/usr/bin/env bash
set -euo pipefail
: "${GITHUB_REPOSITORY:?Missing repository}"
: "${GITHUB_REF_NAME:?Missing version tag}"
: "${GITHUB_RUN_NUMBER:?Missing build number}"
[[ "$GITHUB_REF_NAME" =~ ^v[0-9]+(\.[0-9]+){1,3}$ ]] || { echo 'Invalid version tag'; exit 1; }
artifact_dir="${1:-dist}"
(
    cd "$artifact_dir"
    sha256sum --check --strict aarchive-universal.apk.sha256
)
python3 - "$artifact_dir/update.json" "$GITHUB_REPOSITORY" "$GITHUB_RUN_NUMBER" "${GITHUB_REF_NAME#v}" "$artifact_dir/aarchive-universal.apk" <<'PY'
import hashlib, json, pathlib, sys
metadata = json.load(open(sys.argv[1]))
assert metadata['channel'] == 'github-release'
assert metadata['repository'] == sys.argv[2]
assert metadata['versionCode'] == int(sys.argv[3])
assert metadata['versionName'] == sys.argv[4]
apk = pathlib.Path(sys.argv[5])
assert metadata['schemaVersion'] == 1 and metadata['apk'] == apk.name
assert metadata['size'] == apk.stat().st_size
assert metadata['sha256'] == hashlib.sha256(apk.read_bytes()).hexdigest()
PY
if gh release view "$GITHUB_REF_NAME" --repo "$GITHUB_REPOSITORY" > /dev/null 2>&1; then
    echo "Release $GITHUB_REF_NAME already exists; leaving its assets unchanged."
    exit 0
fi
notes="$(mktemp)"
trap 'rm -f "$notes"' EXIT
cat > "$notes" <<EOF
Universal Android APK for ARMv7, ARMv8, x86 and x86-64 (Android 8.0+).
Updates stay in the versioned GitHub release channel.
Signed with the repository's public development key; testing builds only.
EOF
gh release create "$GITHUB_REF_NAME" "$artifact_dir/aarchive-universal.apk" \
    "$artifact_dir/aarchive-universal.apk.sha256" "$artifact_dir/update.json" \
    --repo "$GITHUB_REPOSITORY" --title "$GITHUB_REF_NAME" --notes-file "$notes" --latest --verify-tag
