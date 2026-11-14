#!/usr/bin/env bash
# CI only: installs the NDK that dev/build-bridge.sh expects into the runner's Android SDK,
# and points ANDROID_NDK_HOME of the later workflow steps at it. GitHub runners ship an
# older NDK as ANDROID_NDK_HOME. sdkmanager checks the download against Google's repository.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="$(sed -n 's/^NDK_VERSION=//p' "$ROOT/dev/build-bridge.sh")"
[ -n "$version" ] || { printf 'install-ndk: no NDK_VERSION in dev/build-bridge.sh\n' >&2; exit 1; }
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { printf 'install-ndk: ANDROID_HOME is not set\n' >&2; exit 1; }
sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
[ -x "$sdkmanager" ] || { printf 'install-ndk: no %s\n' "$sdkmanager" >&2; exit 1; }
"$sdkmanager" --install "ndk;$version" < <(yes) > /dev/null
ndk="$sdk/ndk/$version"
[ -f "$ndk/source.properties" ] || { printf 'install-ndk: %s was not installed\n' "$ndk" >&2; exit 1; }
printf 'install-ndk: NDK %s in %s\n' "$version" "$ndk"
if [ -n "${GITHUB_ENV:-}" ]; then
  printf 'ANDROID_NDK_HOME=%s\n' "$ndk" >> "$GITHUB_ENV"
fi
