#!/usr/bin/env bash
# Android build checks: Lint, unit tests, a debug build, the release App Bundle and the instrumented
# test APK (compiled only; it runs on an emulator in .github/workflows/emulator.yml), then the
# 16 KB checks on the debug APK and on the APKs generated from the bundle.
# Runs every step and reports each failure. Does nothing until the Gradle project exists.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
if [ ! -f gradlew ]; then
  printf 'no Gradle project yet: Android checks skipped\n'
  exit 0
fi
status=0
# --continue runs every task that can still run, so one failure does not hide the others.
# Strict verification overrides a lenient or off setting in gradle.properties.
bash gradlew --no-daemon --continue --stacktrace --dependency-verification strict \
  lint testDebugUnitTest assembleDebug bundleRelease assembleDebugAndroidTest || status=1
# The expected outputs by name, so a missing one fails instead of being skipped.
bash dev/check-16kb.sh app/build/outputs/apk/debug/app-debug.apk \
  app/build/outputs/bundle/release/app-release.aab || status=1
exit "$status"
