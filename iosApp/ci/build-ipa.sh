#!/usr/bin/env bash
# macOS + Xcode only. Used by GitHub Actions / Codemagic / a local Mac.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IOS="$ROOT/iosApp"
DIST="$ROOT/dist/ios"
METHOD="${IOS_EXPORT_METHOD:-development}"
if [[ "$METHOD" == "testflight" || "$METHOD" == "app-store-connect" ]]; then
  METHOD="app-store"
fi
TEAM="${DEVELOPMENT_TEAM:-}"
BUILD_NUMBER="${GITHUB_RUN_NUMBER:-${BUILD_NUMBER:-1}}"
EXPORT_PLIST="$IOS/ExportOptions.${METHOD}.plist"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "iOS IPA must be built on macOS with Xcode. This host is $(uname -s)."
  echo "Use GitHub Actions (macos-15) or Codemagic — see README."
  exit 2
fi

if [[ -z "$TEAM" ]]; then
  echo "DEVELOPMENT_TEAM is required (Apple Developer Team ID)."
  exit 2
fi

if [[ ! -f "$EXPORT_PLIST" ]]; then
  echo "Unknown export method '$METHOD'. Use development | ad-hoc | app-store"
  exit 2
fi

command -v xcodegen >/dev/null || brew install xcodegen
mkdir -p "$DIST"
cd "$IOS"
xcodegen generate

ARCHIVE="$DIST/Auralis.xcarchive"
rm -rf "$ARCHIVE" "$DIST/Auralis.ipa"

xcodebuild \
  -project Auralis.xcodeproj \
  -scheme Auralis \
  -configuration Release \
  -sdk iphoneos \
  -destination "generic/platform=iOS" \
  -archivePath "$ARCHIVE" \
  DEVELOPMENT_TEAM="$TEAM" \
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
  MARKETING_VERSION="${MARKETING_VERSION:-0.1.0}" \
  CODE_SIGN_STYLE=Manual \
  clean archive

xcodebuild \
  -exportArchive \
  -archivePath "$ARCHIVE" \
  -exportOptionsPlist "$EXPORT_PLIST" \
  -exportPath "$DIST"

echo "IPA ready: $DIST/Auralis.ipa"
ls -lh "$DIST"/*.ipa
