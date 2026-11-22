#!/usr/bin/env bash
# Builds the gomobile bridge (engine/bridge) into .build/bridge/bridge.aar for
# arm64-v8a and x86_64, then checks that every library in it has 16 KB-aligned
# LOAD segments. The AAR is never committed. Gradle runs this script through
# the app's buildBridge task; it can also be run on its own.
#
# Needs: ANDROID_NDK_HOME (NDK r30, 30.0.16248370), ANDROID_HOME (or
# ANDROID_SDK_ROOT) with platform android-37.2, and any go: the build runs with
# exactly the Go version engine/go.mod names (GOTOOLCHAIN=go<version>, fetched
# if needed), so a newer local Go does not change the AAR. A toolchain line in
# go.mod would not do that: it is only a minimum, and go mod tidy drops it
# when it equals the go line. gomobile and gobind are pinned by
# the tool directives in engine/go.mod and built from there.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/.build/bridge"
NDK_VERSION=30.0.16248370

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  printf 'ANDROID_NDK_HOME is not set; install NDK %s, e.g.\n  sdkmanager --sdk_root=<writable dir> "ndk;%s"\n' "$NDK_VERSION" "$NDK_VERSION" >&2
  exit 1
fi
installed="$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties" 2>/dev/null || true)"
if [ "$installed" != "$NDK_VERSION" ]; then
  printf 'ANDROID_NDK_HOME has NDK %s, expected %s\n' "${installed:-unknown}" "$NDK_VERSION" >&2
  exit 1
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$ANDROID_HOME" ] || { printf 'ANDROID_HOME is not set\n' >&2; exit 1; }

mkdir -p "$OUT/bin"
cd "$ROOT/engine"
GOTOOLCHAIN="go$(sed -n 's/^go //p' go.mod)"
export GOTOOLCHAIN
go build -o "$OUT/bin/" golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$OUT/bin:$PATH"

start=$(date +%s)
# -checklinkname=0: Syncthing's anet dependency links to Go internals.
# The page-size flags keep 16 KB alignment explicit (NDK r28+ defaults to it).
gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi=34 \
  -javapkg=app.avoska.syncthing \
  -tags=noassets \
  -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0 -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"' \
  -o "$OUT/bridge.aar" ./bridge
rm -f "$OUT/bridge-sources.jar"
printf 'bridge: built %s in %ds\n' "${OUT#"$ROOT"/}/bridge.aar" "$(( $(date +%s) - start ))"

# 16 KB check: every LOAD segment of every library aligned to at least 16 KiB.
readelf="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$OUT/bridge.aar" 'jni/*' -d "$tmp"
fail=0
count=0
for so in "$tmp"/jni/*/*.so; do
  count=$((count + 1))
  name="${so#"$tmp"/jni/}"
  aligns="$("$readelf" -W -l "$so" | awk '$1 == "LOAD" { print $NF }')"
  [ -n "$aligns" ] || { printf 'bridge: %s has no LOAD segments\n' "$name" >&2; fail=1; }
  for align in $aligns; do
    if (( align < 16384 )); then # bash arithmetic reads the 0x prefix
      printf 'bridge: %s has a LOAD segment aligned to %s\n' "$name" "$align" >&2
      fail=1
    fi
  done
  printf 'bridge: %s LOAD alignment: %s\n' "$name" "${aligns//$'\n'/ }"
done
[ "$count" -eq 2 ] || { printf 'bridge: expected 2 libraries, found %d\n' "$count" >&2; fail=1; }
exit "$fail"
