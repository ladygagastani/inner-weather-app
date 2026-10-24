#!/bin/bash
# Puts a release into feed/, which the app reads on every start:
#   feed/version.json  {web, appSha256, runtimeSha256, apk, apkName, apkFile, apkSha256}
#   feed/app.html, feed/runtime.js   Daylight itself (updates in place, no reinstall)
#   feed/inner-weather.apk           only when the Android shell changes (bump VERSION_CODE)
#   ./publish-feed.sh <web version> <apk versionCode> <apk versionName> <apk built WITHOUT SEED>
set -euo pipefail
cd "$(dirname "$0")"
WEB=$1 CODE=$2 NAME=$3 APK=$4
mkdir -p feed
cp web/app.html feed/app.html
cat web/runtime-core.js web/boot.js > feed/runtime.js
cp "$APK" feed/inner-weather.apk
if unzip -l feed/inner-weather.apk | grep -q 'assets/seed.json'; then echo "refusing: APK contains records"; exit 1; fi
sha() { sha256sum "$1" | cut -d' ' -f1; }
cat > feed/version.json <<JSON
{
  "web": $WEB,
  "appSha256": "$(sha feed/app.html)",
  "runtimeSha256": "$(sha feed/runtime.js)",
  "apk": $CODE,
  "apkName": "$NAME",
  "apkFile": "inner-weather.apk",
  "apkSha256": "$(sha feed/inner-weather.apk)"
}
JSON
cat feed/version.json
