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

adb logcat -d > "$OUT/logcat.txt" || true
if adb logcat -d -b crash | grep -q "$PKG"; then
  adb logcat -d -b crash
  fail "the app crashed"
fi

# On 16 KB pages Android runs an app whose libraries are not 16 KB-aligned in page size
# compatibility mode. dumpsys package shows why as pageSizeCompat (the PAGE_SIZE_APP_COMPAT_FLAG_*
# bits of ApplicationInfo), 0 for no reason and no manifest override. The control is a copy of
# the APK with its libraries realigned to 4 KB: it must get a nonzero value, or the check is blind.
compat() { adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/^ *pageSizeCompat=//p' | head -n 1; }

# control_apk <out>: the debug APK with every entry stored and every library starting 4 KB past a
# 16 KB boundary, signed with the debug key. Fails unless zipalign -P 16 rejects it.
control_apk() {
  local sdk tools
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  python3 -I - "$APK" "$1.unsigned" <<'PY' || return 1
import struct, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as src, zipfile.ZipFile(sys.argv[2], "w") as out:
    for info in src.infolist():
        if info.filename.startswith("META-INF/"):
            continue
        new = zipfile.ZipInfo(info.filename, info.date_time)
        # Data of libraries starts at 4 KB past a 16 KB boundary, of other files at 4 bytes.
        # Padding record as zipalign writes it (ID 0xD935): 4 header bytes, then zeros.
        data_start = out.fp.tell() + 30 + len(info.filename.encode())
        if info.filename.endswith(".so"):
            pad = (4096 - data_start) % 16384
        else:
            pad = -data_start % 4
        if pad:
            pad += 16384 if info.filename.endswith(".so") and pad < 4 else (4 if pad < 4 else 0)
            new.extra = struct.pack("<HH", 0xD935, pad - 4) + bytes(pad - 4)
        out.writestr(new, src.read(info), zipfile.ZIP_STORED)
PY
  if "$tools/zipalign" -c -P 16 4 "$1.unsigned" >/dev/null; then
    printf 'the control APK is 16 KB-aligned\n' >&2
    return 1
  fi
  "$tools/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out "$1" "$1.unsigned"
}

if [ "$page" = 16384 ]; then
  value="$(compat)"
  printf 'pageSizeCompat of the app: %s\n' "${value:-missing}"
  [ "$value" = 0 ] || fail "pageSizeCompat is '${value:-missing}', expected 0: page size compatibility mode"
  if control_apk "$OUT/control.apk" && adb uninstall "$PKG" >/dev/null && adb install -t "$OUT/control.apk" >/dev/null; then
    control="$(compat)"
    printf 'pageSizeCompat of the 4 KB-aligned control: %s\n' "${control:-missing}"
    [ -n "$control" ] && [ "$control" != 0 ] || fail "the 4 KB-aligned control got pageSizeCompat '${control:-missing}'"
    adb uninstall "$PKG" >/dev/null || true
  else
    fail "could not build and install the 4 KB-aligned control APK"
  fi
fi
exit "$status"
