#!/usr/bin/env bash
# Use a disposable emulator: this test replaces dev.aarchive and its test package.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a disposable emulator}"
[[ "$ANDROID_SERIAL" == emulator-* ]] || { echo 'Updater installation tests require a disposable emulator.'; exit 1; }
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
assets="$project_dir/app/build/update-test-assets"
mkdir -p "$assets"
./gradlew assembleMaster -PmasterBuildNumber=7 -PupdateChannel=github-master
cp app/build/outputs/apk/master/app-master.apk "$temporary/installed.apk"
./gradlew assembleMaster -PmasterBuildNumber=8 -PupdateChannel=github-master
cp app/build/outputs/apk/master/app-master.apk "$assets/candidate.apk"
keytool -genkeypair -keystore "$temporary/wrong-key.p12" -storetype PKCS12 -storepass android \
    -keypass android -alias test -dname 'CN=Updater Test' -keyalg RSA -validity 30
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$temporary/wrong-key.p12" \
    --ks-key-alias test --ks-pass pass:android --out "$assets/wrong-signer.apk" "$assets/candidate.apk"
./gradlew assembleVersioned -PmasterBuildNumber=8 -PupdateChannel=github-release -PappVersion=1.2.3
cp app/build/outputs/apk/versioned/app-versioned.apk "$assets/wrong-channel.apk"
./gradlew assembleMasterAndroidTest -PmasterBuildNumber=8 -PupdateChannel=github-master
adb uninstall dev.aarchive.test > /dev/null 2>&1 || true
adb uninstall dev.aarchive > /dev/null 2>&1 || true
adb install "$temporary/installed.apk"
adb install app/build/outputs/apk/androidTest/master/app-master-androidTest.apk
result="$(adb shell am instrument -w dev.aarchive.test/dev.aarchive.UpdateInstrumentation)"
echo "$result"
[[ "$result" == *'Updater passed '* ]] && [[ "$result" != *'INSTRUMENTATION_CODE: 0'* ]]
