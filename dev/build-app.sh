#!/usr/bin/env bash
# Builds the Syncthing bridge AAR (through Gradle's buildBridge task, dev/build-bridge.sh), the
# debug app and an unsigned, minified release app (exercises R8), runs Android lint and the JVM
# unit tests, then checks 16 KB page support of both APKs: native libraries stored uncompressed,
# 16 KB ZIP alignment (zipalign -P 16) and 16 KB ELF LOAD alignment.
# Needs ANDROID_HOME (or ANDROID_SDK_ROOT) pointing at an SDK with platform android-36 and
# build-tools 36.0.0, and ANDROID_NDK_HOME pointing at NDK 30.0.16248370.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ]; then
  printf 'ANDROID_HOME is not set\n' >&2
  exit 1
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  printf 'ANDROID_NDK_HOME is not set (NDK 30.0.16248370, see dev/build-bridge.sh)\n' >&2
  exit 1
fi

./gradlew --console=plain assembleDebug assembleRelease lintDebug testDebugUnitTest "$@"

zipalign="$sdk/build-tools/36.0.0/zipalign"
readelf="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail=0
for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk; do
  if ! "$zipalign" -c -P 16 4 "$apk"; then
    printf '16 KB: %s fails zipalign -c -P 16\n' "$apk" >&2
    fail=1
  fi
  libs="$(unzip -Z1 "$apk" 'lib/*.so' 2>/dev/null || true)"
  [ -n "$libs" ] || { printf '16 KB: %s has no native libraries\n' "$apk" >&2; fail=1; }
  for lib in $libs; do
    # Captured first: grep -q stops reading early, and pipefail would then see unzip's SIGPIPE.
    info="$(unzip -Zv "$apk" "$lib")"
    if ! grep -q 'compression method: *none' <<<"$info"; then
      printf '16 KB: %s: %s is compressed\n' "$apk" "$lib" >&2
      fail=1
    fi
    unzip -q -o "$apk" "$lib" -d "$tmp"
    if ! aligns="$("$readelf" -W -l "$tmp/$lib" | awk '$1 == "LOAD" { print $NF }')"; then
      printf '16 KB: %s: llvm-readelf failed on %s\n' "$apk" "$lib" >&2
      fail=1
    fi
    [ -n "$aligns" ] || { printf '16 KB: %s: %s has no LOAD segments\n' "$apk" "$lib" >&2; fail=1; }
    for align in $aligns; do
      if (( align < 16384 )); then # bash arithmetic reads the 0x prefix
        printf '16 KB: %s: %s has a LOAD segment aligned to %s\n' "$apk" "$lib" "$align" >&2
        fail=1
      fi
    done
  done
  printf '16 KB: %s checked (%s)\n' "$apk" "${libs//$'\n'/ }"
done
[ "$fail" -eq 0 ] || exit 1
printf 'APKs: app/build/outputs/apk/debug/app-debug.apk, app/build/outputs/apk/release/app-release-unsigned.apk\n'
printf 'Lint report: app/build/reports/lint-results-debug.html\n'
