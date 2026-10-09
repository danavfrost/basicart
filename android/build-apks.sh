#!/bin/bash
# Builds BasicArt.apk (64-bit), BasicArt-32bit.apk (armeabi-v7a) and the release AAB.
# Release builds are debug-signed until a store key is configured.
set -euo pipefail
cd "$(dirname "$0")"
OUT=..
./gradlew -q :app:clean
./gradlew -q -Pabi=64 :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk "$OUT/BasicArt.apk"
./gradlew -q -Pabi=32 :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk "$OUT/BasicArt-32bit.apk"
./gradlew -q :app:bundleRelease
echo "AAB: app/build/outputs/bundle/release/app-release.aab"
for f in "$OUT/BasicArt.apk" "$OUT/BasicArt-32bit.apk"; do
  echo "$f: $(unzip -l "$f" | grep -oE 'lib/[^/]+' | sort -u | tr '\n' ' ')"
done
