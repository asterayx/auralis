#!/usr/bin/env bash
# Compile the iOS app without Apple signing secrets (CI on push).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IOS="$ROOT/iosApp"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Unsigned iOS compile needs macOS + Xcode. This host is $(uname -s)."
  exit 2
fi

command -v xcodegen >/dev/null || brew install xcodegen
cd "$IOS"
xcodegen generate

xcodebuild \
  -project Auralis.xcodeproj \
  -scheme Auralis \
  -configuration Release \
  -sdk iphoneos \
  -destination "generic/platform=iOS" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  build
