#!/usr/bin/env bash
# Builds the debug app and an unsigned, minified release app (exercises R8),
# runs Android lint and the JVM unit tests.
# Needs ANDROID_HOME (or ANDROID_SDK_ROOT) pointing at an SDK with platform android-36.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
  printf 'ANDROID_HOME is not set\n' >&2
  exit 1
fi

./gradlew --console=plain assembleDebug assembleRelease lintDebug testDebugUnitTest "$@"
printf 'APKs: app/build/outputs/apk/debug/app-debug.apk, app/build/outputs/apk/release/app-release-unsigned.apk\n'
printf 'Lint report: app/build/reports/lint-results-debug.html\n'
