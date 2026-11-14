#!/usr/bin/env bash
# Build the debug APK with the local toolchain (no Android Studio needed).
#
#   ./build-apk.sh              # debug APK
#   ./build-apk.sh release      # release APK (unsigned)
#   ./build-apk.sh clean        # wipe build/ and caches of the module
#
# Override any path through env vars if the toolchain moves:
#   TOOLCHAIN=... JAVA_HOME=... GRADLE_USER_HOME=... ANDROID_HOME=... ./build-apk.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLCHAIN="${TOOLCHAIN:-/d/git-workspace/casting-android/.toolchain}"

export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk/jdk-17.0.13+11}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLCHAIN/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# Reuse the shared Gradle cache so Compose/AGP don't get re-downloaded.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TOOLCHAIN/gradle-home}"
GRADLE_BIN="$TOOLCHAIN/gradle/gradle-8.7/bin/gradle"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK not found at $JAVA_HOME" >&2
  exit 1
fi
if [[ ! -x "$GRADLE_BIN" ]]; then
  echo "Gradle not found at $GRADLE_BIN" >&2
  exit 1
fi
if [[ ! -d "$ANDROID_HOME/platforms/android-34" ]]; then
  echo "Android SDK platform 34 missing under $ANDROID_HOME" >&2
  exit 1
fi

# local.properties is machine-specific; keep it in sync with the SDK we use.
printf 'sdk.dir=%s\n' "$(echo "$ANDROID_HOME" | sed 's|^/\([a-z]\)/|\1:/|')" > "$HERE/local.properties"

TASK=":app:assembleDebug"
case "${1:-debug}" in
  debug)   TASK=":app:assembleDebug" ;;
  release) TASK=":app:assembleRelease" ;;
  clean)   TASK="clean" ;;
  *)       echo "usage: $0 [debug|release|clean]" >&2; exit 2 ;;
esac

cd "$HERE"
# --offline: everything this app needs is already in the toolchain cache, and it
# turns a missing artifact into an immediate, clear error instead of a 10 minute
# network timeout. Drop the flag if you ever add a dependency.
"$GRADLE_BIN" --no-daemon --offline --console=plain \
  -Dorg.gradle.internal.http.connectionTimeout=30000 \
  -Dorg.gradle.internal.http.socketTimeout=120000 \
  "$TASK"

if [[ "$TASK" != "clean" ]]; then
  APK=$(find "$HERE/app/build/outputs/apk" -name "*.apk" -newermt '-10 minutes' 2>/dev/null | head -1)
  if [[ -n "${APK:-}" ]]; then
    echo
    echo "APK: $APK"
    ls -lh "$APK" | awk '{print "     "$5}'
    echo "安装: \"$ANDROID_HOME/platform-tools/adb\" install -r \"$APK\""
  fi
fi
