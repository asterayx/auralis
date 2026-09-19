#!/usr/bin/env bash
# Produce Shared.framework so Xcode `import Shared` and -framework Shared resolve.
# Called from the Xcode pre-build phase (Xcode sets CONFIGURATION / SDK_NAME) and
# from ios-ipa CI. Static KMP skips embedAndSign; assemble still writes the
# framework under shared/build/xcode-frameworks/.
set -euo pipefail

if [ -n "${SRCROOT:-}" ]; then
  ROOT="$(cd "$SRCROOT/.." && pwd)"
else
  ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
fi
cd "$ROOT"

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home)"
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ -n "${CONFIGURATION:-}" ] && [ -n "${SDK_NAME:-}" ]; then
  ./gradlew :shared:embedAndSignAppleFrameworkForXcode
  CANDIDATES=(
    "$ROOT/shared/build/xcode-frameworks/${CONFIGURATION}/${SDK_NAME}/Shared.framework"
  )
else
  ./gradlew :shared:linkReleaseFrameworkIosArm64
  CANDIDATES=(
    "$ROOT/shared/build/bin/iosArm64/releaseFramework/Shared.framework"
  )
fi

SRC=""
for candidate in "${CANDIDATES[@]}"; do
  if [ -d "$candidate" ]; then
    SRC="$candidate"
    break
  fi
done

if [ -z "$SRC" ]; then
  SRC="$(find "$ROOT/shared/build" -name Shared.framework -type d -print -quit 2>/dev/null || true)"
fi

if [ -z "$SRC" ] || [ ! -d "$SRC" ]; then
  echo "error: Shared.framework was not produced (looked under shared/build)." >&2
  exit 1
fi

copy_fw() {
  local dest_dir="$1"
  mkdir -p "$dest_dir"
  rm -rf "$dest_dir/Shared.framework"
  if command -v ditto >/dev/null 2>&1; then
    ditto "$SRC" "$dest_dir/Shared.framework"
  else
    cp -R "$SRC" "$dest_dir/Shared.framework"
  fi
}

# Stable path for FRAMEWORK_SEARCH_PATHS + Xcode SourceKit after the first build.
copy_fw "$ROOT/iosApp/Frameworks"
if [ -n "${BUILT_PRODUCTS_DIR:-}" ]; then
  copy_fw "$BUILT_PRODUCTS_DIR"
fi

echo "Shared.framework ready: $SRC"
