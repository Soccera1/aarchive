#!/usr/bin/env bash
set -euo pipefail
: "${GITHUB_REPOSITORY:?This script runs in GitHub Actions}"
: "${GITHUB_SHA:?Missing workflow commit SHA}"
: "${GITHUB_RUN_ID:?Missing workflow run ID}"
: "${GITHUB_RUN_NUMBER:?Missing workflow run number}"
artifact_dir="${1:-dist}"
apk_name="aarchive-master-universal.apk"

# Only publish the current master head, including when an older run is retried.
latest_sha="$(gh api "repos/$GITHUB_REPOSITORY/git/ref/heads/master" --jq '.object.sha')"
if [[ "$latest_sha" != "$GITHUB_SHA" ]]; then
    echo "Skipping superseded master build $GITHUB_SHA."
    exit 0
fi

(
    cd "$artifact_dir"
    sha256sum --check --strict "$apk_name.sha256"
)

notes_file="$(mktemp)"
trap 'rm -f "$notes_file"' EXIT
server_url="${GITHUB_SERVER_URL:-https://github.com}"
cat > "$notes_file" <<EOF
Rolling development build of the master branch.

Commit: [$GITHUB_SHA]($server_url/$GITHUB_REPOSITORY/commit/$GITHUB_SHA)
Build: [#$GITHUB_RUN_NUMBER]($server_url/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID)

One installable APK for x86, x86-64, ARMv7, and ARMv8 (Android 8.0+).
Signed with the repository's public development key. For testing only.
EOF

# Use an explicit tag ref: the branch and the rolling release are both "master".
git push origin "$GITHUB_SHA:refs/tags/master" --force

if gh release view master --repo "$GITHUB_REPOSITORY" --json tagName > /dev/null 2>&1; then
    gh release upload master "$artifact_dir/$apk_name" "$artifact_dir/$apk_name.sha256" \
        --repo "$GITHUB_REPOSITORY" --clobber
    gh release edit master --repo "$GITHUB_REPOSITORY" \
        --title master --notes-file "$notes_file" --prerelease --latest=false --target "$GITHUB_SHA"
else
    gh release create master "$artifact_dir/$apk_name" "$artifact_dir/$apk_name.sha256" \
        --repo "$GITHUB_REPOSITORY" --title master --notes-file "$notes_file" \
        --prerelease --latest=false --verify-tag --target "$GITHUB_SHA"
fi
