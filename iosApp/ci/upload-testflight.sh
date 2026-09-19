#!/usr/bin/env bash
# Upload a signed App Store IPA to TestFlight via App Store Connect API key.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IPA="${1:-$ROOT/dist/ios/Auralis.ipa}"
KEY_ID="${APP_STORE_CONNECT_KEY_ID:-}"
ISSUER="${APP_STORE_CONNECT_ISSUER_ID:-}"
P8="${APP_STORE_CONNECT_API_KEY_P8:-}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "TestFlight upload must run on macOS (Transporter / Fastlane)."
  exit 2
fi

if [[ ! -f "$IPA" ]]; then
  echo "IPA not found: $IPA"
  exit 2
fi

if [[ -z "$KEY_ID" || -z "$ISSUER" || -z "$P8" ]]; then
  echo "Need APP_STORE_CONNECT_KEY_ID, APP_STORE_CONNECT_ISSUER_ID, APP_STORE_CONNECT_API_KEY_P8."
  echo "Create the key in App Store Connect → Users and Access → Integrations → App Store Connect API."
  exit 2
fi

AUTH_DIR="$HOME/.appstoreconnect/private_keys"
mkdir -p "$AUTH_DIR"
KEY_FILE="$AUTH_DIR/AuthKey_${KEY_ID}.p8"
if [[ "$P8" == *"BEGIN PRIVATE KEY"* ]]; then
  printf '%s\n' "$P8" > "$KEY_FILE"
else
  printf '%s' "$P8" | base64 --decode > "$KEY_FILE"
fi
chmod 600 "$KEY_FILE"

if command -v bundle >/dev/null && [[ -f "$ROOT/iosApp/fastlane/Fastfile" ]]; then
  cd "$ROOT/iosApp"
  bundle exec fastlane testflight_upload ipa:"$IPA" || fastlane testflight_upload ipa:"$IPA"
else
  # Xcode 16+: altool still accepts --upload-app for TestFlight.
  xcrun altool --upload-app \
    --type ios \
    --file "$IPA" \
    --apiKey "$KEY_ID" \
    --apiIssuer "$ISSUER"
fi

echo "Uploaded $IPA to App Store Connect. Processing on TestFlight usually takes 5–15 minutes."
