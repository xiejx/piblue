#!/usr/bin/env bash
# Build the APK from a terminal - Android Studio is optional.
#
#   ./build-apk.sh              # debug APK (installable as-is)
#   ./build-apk.sh release      # release APK (unsigned)
#   ./build-apk.sh clean        # wipe this module's build/
#
# Requirements: JDK 17 and Android SDK Platform 34. Details in docs/android.md.
#
# Both are located the way Gradle itself expects them:
#
#   JDK          $JAVA_HOME -> `java` on PATH -> Android Studio's bundled JBR
#   Android SDK  $ANDROID_HOME or $ANDROID_SDK_ROOT -> sdk.dir in
#                local.properties -> the per-OS default SDK directory
#
# If your SDK/JDK live somewhere else, put the paths in android/env.local
# (git-ignored), and this script will pick them up:
#
#   JAVA_HOME=/c/Program Files/Android/Android Studio/jbr
#   ANDROID_HOME=/c/Users/me/AppData/Local/Android/Sdk
#   GRADLE_USER_HOME=/d/ci/gradle-home     # optional: reuse an existing cache
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

if [[ -f "$HERE/env.local" ]]; then
  # shellcheck disable=SC1091
  source "$HERE/env.local"
fi

# env.local sets plain shell variables; anything Gradle itself reads has to be
# exported before the wrapper can see it.
if [[ -n "${GRADLE_USER_HOME:-}" ]]; then
  export GRADLE_USER_HOME
fi

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- JDK --------
find_jdk() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    return
  fi
  if command -v java >/dev/null 2>&1; then
    # Ask the JVM itself: Gradle wants a JAVA_HOME, not just a binary on PATH.
    JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 \
      | sed -n 's/^ *java\.home = //p' | head -1)"
    [[ -n "$JAVA_HOME" ]] && export JAVA_HOME && return
  fi
  local candidate
  for candidate in \
    "/c/Program Files/Android/Android Studio/jbr" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/opt/android-studio/jbr"
  do
    if [[ -x "$candidate/bin/java" ]]; then
      JAVA_HOME="$candidate"
      export JAVA_HOME
      return
    fi
  done
}

find_jdk
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] || die "no JDK found.
  Install JDK 17 (Temurin / OpenJDK / Android Studio's bundled JBR), then either
  export JAVA_HOME=/path/to/jdk-17 or put it in $HERE/env.local"

# Whatever the source - env.local, an inherited variable, a discovered JBR -
# the wrapper only sees it if it is exported.
export JAVA_HOME

JAVA_MAJOR="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 \
  | sed -E 's/.*"([0-9]+).*/\1/')"
if [[ "${JAVA_MAJOR:-0}" != "1" && "${JAVA_MAJOR:-0}" -lt 17 ]]; then
  die "JDK 17+ required, found ${JAVA_MAJOR} at $JAVA_HOME"
fi

# ---------------------------------------------------------- Android SDK ------
unix_path() {
  # local.properties holds a Windows path (d:/...) even under Git Bash.
  if command -v cygpath >/dev/null 2>&1 && [[ "$1" =~ ^[A-Za-z]: ]]; then
    cygpath -u "$1"
  else
    printf '%s' "$1"
  fi
}

find_sdk() {
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    return
  fi
  local from_props
  from_props="$(sed -n 's/^sdk\.dir=//p' "$HERE/local.properties" 2>/dev/null | head -1)"
  if [[ -n "$from_props" ]]; then
    ANDROID_HOME="$(unix_path "$from_props")"
    [[ -d "$ANDROID_HOME" ]] && return
    ANDROID_HOME=""
  fi
  local candidate
  for candidate in \
    "${LOCALAPPDATA:-}/Android/Sdk" \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk"
  do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
      ANDROID_HOME="$candidate"
      return
    fi
  done
}

find_sdk
[[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]] || die "no Android SDK found.
  Install it through Android Studio's SDK Manager, or point ANDROID_HOME at an
  existing one (also possible: $HERE/env.local)"

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

[[ -d "$ANDROID_HOME/platforms/android-34" ]] || die \
  "Android SDK Platform 34 is missing under $ANDROID_HOME
  Install it with: sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\""

# Gradle cares about local.properties more than about ANDROID_HOME, so a stale
# one is worse than none. Only create it when it does not exist at all - never
# overwrite what Android Studio wrote.
if [[ ! -f "$HERE/local.properties" ]]; then
  sdk_value="$ANDROID_HOME"
  command -v cygpath >/dev/null 2>&1 && sdk_value="$(cygpath -w "$ANDROID_HOME")"
  printf 'sdk.dir=%s\n' "$sdk_value" > "$HERE/local.properties"
fi

# --------------------------------------------------------------- build -------
case "${1:-debug}" in
  debug)   TASK=":app:assembleDebug" ;;
  release) TASK=":app:assembleRelease" ;;
  clean)   TASK="clean" ;;
  *)       printf 'usage: %s [debug|release|clean]\n' "$0" >&2; exit 2 ;;
esac

# The wrapper pins Gradle 8.7 (see gradle/wrapper/gradle-wrapper.properties) and
# downloads it on first use; no system-wide Gradle install is needed.
"$HERE/gradlew" --no-daemon --console=plain "$TASK"

if [[ "$TASK" != "clean" ]]; then
  APK="$(find "$HERE/app/build/outputs/apk" -name '*.apk' -newermt '-10 minutes' 2>/dev/null | head -1)"
  if [[ -n "${APK:-}" ]]; then
    printf '\nAPK: %s\n' "$APK"
    ls -lh "$APK" | awk '{print "     "$5}'
    if [[ -x "$ANDROID_HOME/platform-tools/adb" ]]; then
      printf 'install: "%s" install -r "%s"\n' "$ANDROID_HOME/platform-tools/adb" "$APK"
    else
      printf 'install: adb install -r "%s"\n' "$APK"
    fi
  fi
fi
