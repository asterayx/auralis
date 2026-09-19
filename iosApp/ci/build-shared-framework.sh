#!/bin/bash
# Xcode Run Script / ios-ipa: build the static Shared.framework.
# Official task is embedAndSignAppleFrameworkForXcode (needs Xcode env).
# Static binaries skip the embed step; link* still writes the framework.
set -euo pipefail

if [ "${OVERRIDE_KOTLIN_BUILD_IDE_PATHS:-}" = "YES" ]; then
  exit 0
fi

if [ -n "${SRCROOT:-}" ]; then
  ROOT="$(cd "$SRCROOT/.." && pwd)"
else
  ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
fi
cd "$ROOT"

if [ ! -x ./gradlew ]; then
  echo "error: ./gradlew missing at $ROOT" >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  if JH="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
    export JAVA_HOME="$JH"
  elif JH="$(/usr/libexec/java_home 2>/dev/null)"; then
    export JAVA_HOME="$JH"
  fi
fi
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21 \
                   /opt/homebrew/opt/openjdk /usr/local/opt/openjdk; do
    if [ -d "$candidate" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# Do not reuse a Gradle daemon started outside Xcode (missing SDK_NAME / ARCHS).
GRADLE=(./gradlew --no-daemon --stacktrace)

config="${CONFIGURATION:-Debug}"
case "$config" in
  Release|release) flavor=Release; bin_flavor=release ;;
  *) flavor=Debug; bin_flavor=debug ;;
esac

platform="${PLATFORM_NAME:-iphoneos}"
case "$platform" in
  iphonesimulator) link_task="link${flavor}FrameworkIosSimulatorArm64" ;;
  macosx) link_task="link${flavor}FrameworkMacosArm64" ;;
  *) link_task="link${flavor}FrameworkIosArm64" ;;
esac

echo "Building Shared.framework ($config / ${SDK_NAME:-unknown} / $link_task)"
# link* always writes bin/<target>/<debug|release>Framework/Shared.framework.
# embedAndSign additionally symlinks into xcode-frameworks/ (embed is skipped
# for static). Do not fail Cmd+B if embedAndSign hits sandbox / codesign.
"${GRADLE[@]}" ":shared:${link_task}"
if [ -n "${SDK_NAME:-}" ] && [ -n "${CONFIGURATION:-}" ]; then
  "${GRADLE[@]}" ":shared:embedAndSignAppleFrameworkForXcode" \
    || echo "warning: embedAndSignAppleFrameworkForXcode failed; using ${link_task} output"
fi

framework=""
for candidate in \
  ${SDK_NAME:+"$ROOT/shared/build/xcode-frameworks/${config}/${SDK_NAME}/Shared.framework"} \
  "$ROOT/shared/build/bin/iosArm64/${bin_flavor}Framework/Shared.framework" \
  "$ROOT/shared/build/bin/iosSimulatorArm64/${bin_flavor}Framework/Shared.framework" \
  "$ROOT/shared/build/bin/macosArm64/${bin_flavor}Framework/Shared.framework"
do
  if [ -d "$candidate" ]; then
    framework="$candidate"
    break
  fi
done

if [ -z "$framework" ]; then
  echo "error: Shared.framework missing after Gradle." >&2
  echo "JAVA_HOME=${JAVA_HOME:-unset} CONFIGURATION=${CONFIGURATION:-unset} SDK_NAME=${SDK_NAME:-unset} PLATFORM_NAME=${PLATFORM_NAME:-unset}" >&2
  ls -la "$ROOT/shared/build/xcode-frameworks" 2>/dev/null || true
  ls -la "$ROOT/shared/build/bin" 2>/dev/null || true
  exit 1
fi

copy_fw() {
  local dest_dir="$1"
  mkdir -p "$dest_dir"
  rm -rf "$dest_dir/Shared.framework"
  if command -v ditto >/dev/null 2>&1; then
    ditto "$framework" "$dest_dir/Shared.framework"
  else
    cp -R "$framework" "$dest_dir/Shared.framework"
  fi
}

# Best-effort copies so import Shared resolves; do not fail the phase if ditto
# cannot write DerivedData (sandbox) as long as the Gradle product exists.
copy_fw "$ROOT/iosApp/Frameworks" || true
if [ -n "${BUILT_PRODUCTS_DIR:-}" ]; then
  copy_fw "$BUILT_PRODUCTS_DIR" || true
fi

echo "Shared.framework ready: $framework"
