#!/usr/bin/env bash
# Build signed release artifacts:
#   sideload APK -> /workspace/le-sales-android-out/le-sales.apk          (give to salespeople directly)
#   Play AAB     -> /workspace/le-sales-android-out/le-sales-play-vN.aab  (upload to Play Console)
# Signing key: /workspace/le-sales-android-keys/keystore.properties (override with LE_KEYSTORE_PROPS). Never commit it.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-/workspace/android-sdk}
npm ci --no-audit --no-fund >/dev/null 2>&1 || npm install --no-audit --no-fund
npx cap sync android
(cd android && ./gradlew assembleSideloadRelease bundlePlayRelease --no-daemon -q)
VC=$(grep -o 'versionCode [0-9]*' android/app/build.gradle | awk '{print $2}')
OUT=/workspace/le-sales-android-out; mkdir -p $OUT
cp android/app/build/outputs/apk/sideload/release/app-sideload-release.apk $OUT/le-sales.apk
cp android/app/build/outputs/bundle/playRelease/app-play-release.aab $OUT/le-sales-play-v$VC.aab
"$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs $OUT/le-sales.apk | head -2
jarsigner -verify $OUT/le-sales-play-v$VC.aab | tail -1
ls -la $OUT
