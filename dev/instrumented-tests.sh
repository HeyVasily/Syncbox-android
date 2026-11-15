#!/usr/bin/env bash
# Instrumented tests (app/src/androidTest) on an emulator: installs the debug app and its test
# APK fresh, runs them with am instrument, and fails on a failed test or an app crash. Not
# Gradle's connectedDebugAndroidTest: that downloads the Unified Test Platform at test time,
# outside gradle/verification-metadata.xml.
#
# Usage: bash dev/instrumented-tests.sh             builds, then runs on an emulator from
#                                                   android-broker (E2E_API, default 36)
#        bash dev/instrumented-tests.sh --device    runs the built APKs on the connected device
#                                                   (ANDROID_SERIAL); CI uses this
# Env: EXPECT_PAGE_SIZE  fail unless the device's page size is this (4096 or 16384).
# Results: .build/instrumented/ (am instrument output, package dump, logcat).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/.build/instrumented"
PKG=app.avoska
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

if [ "${1:-}" != --device ]; then
  command -v android-broker >/dev/null || { printf 'android-broker is required; in CI use --device\n' >&2; exit 1; }
  cd "$ROOT"
  ./gradlew --console=plain -q --dependency-verification strict assembleDebug assembleDebugAndroidTest
  exec android-broker run --device phone --api "${E2E_API:-36}" --wait 900 -- bash "$ROOT/dev/instrumented-tests.sh" --device
fi

for f in "$APK" "$TEST_APK"; do
  [ -f "$f" ] || { printf 'missing %s: run assembleDebug assembleDebugAndroidTest first\n' "$f" >&2; exit 1; }
done
rm -rf "$OUT"
mkdir -p "$OUT"
status=0
fail() { printf '::error::%s\n' "$*"; status=1; }

adb wait-for-device
for _ in $(seq 300); do
  [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break
  sleep 1
done
page="$(adb shell getconf PAGE_SIZE | tr -d '\r')"
printf 'device: %s, page size %s\n' "$(adb shell getprop ro.build.fingerprint | tr -d '\r')" "$page"
if [ -n "${EXPECT_PAGE_SIZE:-}" ] && [ "$page" != "$EXPECT_PAGE_SIZE" ]; then
  fail "page size is $page, expected $EXPECT_PAGE_SIZE"
fi

# A fresh install: the tests check the first launch.
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb uninstall "$PKG.test" >/dev/null 2>&1 || true
adb install -t "$APK" >/dev/null
adb install -t "$TEST_APK" >/dev/null
adb logcat -c || true
start=$(date +%s)
adb shell am instrument -w -e pageSize "$page" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" |
  tr -d '\r' | tee "$OUT/instrument.txt"
printf 'instrumented tests: %ds\n' "$(( $(date +%s) - start ))"
grep -qE '^OK \([1-9][0-9]* tests?\)$' "$OUT/instrument.txt" || fail "instrumented tests failed (see above)"

adb shell dumpsys package "$PKG" | tr -d '\r' > "$OUT/package.txt" || true
printf 'package flags:\n'
grep -iE 'flags=|pageSize|compat' "$OUT/package.txt" | sed 's/^ */  /' || true
adb logcat -d > "$OUT/logcat.txt" || true
if adb logcat -d -b crash | grep -q "$PKG"; then
  adb logcat -d -b crash
  fail "the app crashed"
fi
exit "$status"
