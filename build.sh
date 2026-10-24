#!/bin/bash
# Builds the Inner Weather APK without Gradle (dl.google.com isn't reachable from where this is built).
# Needs Ubuntu packages: aapt dalvik-exchange zipalign apksigner, plus ANDROID_JAR = a framework jar with
# resources.arsc (robolectric android-all 14, from maven-central.storage-download.googleapis.com).
#
#   ANDROID_JAR=... KEYSTORE=... KS_PASS=... VERSION_CODE=2 VERSION_NAME=2.0 WEB_VERSION=1 [SEED=seed.json] OUT=x.apk ./build.sh
#
# SEED (optional) is a private copy of the records to start from on first launch. Never use it for feed builds.
set -euo pipefail
cd "$(dirname "$0")"
BT=/usr/lib/android-sdk/build-tools/debian
: "${ANDROID_JAR:?}" "${KEYSTORE:?}" "${KS_PASS:?}" "${VERSION_CODE:?}" "${VERSION_NAME:?}" "${WEB_VERSION:?}" "${OUT:?}"
B=android/build
rm -rf $B && mkdir -p $B/res $B/gen $B/classes $B/dex $B/assets/web
cp web/app.html $B/assets/web/app.html
cat web/runtime-core.js web/boot.js > $B/assets/web/runtime.js
echo "$WEB_VERSION" > $B/assets/web/version
if [ -n "${SEED:-}" ]; then cp "$SEED" $B/assets/seed.json; fi
$BT/aapt2 compile --dir android/res -o $B/res/
$BT/aapt2 link -o $B/base.apk -I "$ANDROID_JAR" --manifest android/AndroidManifest.xml -A $B/assets \
  --java $B/gen --min-sdk-version 24 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  $B/res/*.flat --auto-add-overlay
javac --release 8 -cp "$ANDROID_JAR" -Xlint:-options -encoding UTF-8 -d $B/classes $(find android/src $B/gen -name '*.java')
dalvik-exchange --dex --min-sdk-version=24 --output=$B/dex/classes.dex $B/classes
cp $B/base.apk $B/unaligned.apk
(cd $B/dex && zip -q -j ../unaligned.apk classes.dex)
$BT/zipalign -f -p 4 $B/unaligned.apk $B/aligned.apk
$BT/apksigner sign --ks "$KEYSTORE" --ks-pass env:KS_PASS --key-pass env:KS_PASS --out "$OUT" $B/aligned.apk
echo "Built $OUT"
