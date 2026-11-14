#!/usr/bin/env bash
# Retakes docs/design/screenshots/<scene>-<light|dark>.png on an emulator from android-broker.
# Builds the debug app, opens each debug scene (app/src/debug/.../DebugHooks.kt) in both
# themes, and saves the PNGs at 540 x 960 with an 8-bit palette (needs ImageMagick).
# Usage: bash dev/screenshots.sh [scene...]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Keep in sync with DebugHooks.scenes.
all_scenes=(files files-on-device files-syncing files-menu files-remove files-offline
  settings sync-network device-id add-device pairing-waiting folder-offer)

# Runs on the leased emulator: android-broker sets ANDROID_SERIAL and ADB_SERVER_SOCKET.
capture() {
  local apk=$1 out=$2 theme scene
  shift 2
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
  adb shell getprop ro.build.fingerprint
  adb install -r -t "$apk" >/dev/null
  # SystemUI demo mode: fixed clock, full battery and Wi-Fi, no notification icons.
  adb shell settings put global sysui_demo_allowed 1
  local demo=(am broadcast -a com.android.systemui.demo -e command)
  adb shell "${demo[@]}" enter >/dev/null
  adb shell "${demo[@]}" clock -e hhmm 1200 >/dev/null
  adb shell "${demo[@]}" battery -e level 100 -e plugged false >/dev/null
  adb shell "${demo[@]}" network -e wifi show -e level 4 -e fully true >/dev/null
  adb shell "${demo[@]}" notifications -e visible false >/dev/null
  for theme in light dark; do
    if [ "$theme" = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
    sleep 2
    for scene in "$@"; do
      adb shell am force-stop app.avoska
      adb shell am start -W -n app.avoska/.MainActivity --es scene "$scene" >/dev/null
      sleep 3
      adb exec-out screencap -p > "$out/$scene-$theme.png"
      printf 'captured %s-%s\n' "$scene" "$theme"
    done
  done
  adb shell cmd uimode night no
  adb shell "${demo[@]}" exit >/dev/null
  if adb logcat -d -b crash | grep -q 'app.avoska'; then
    adb logcat -d -b crash
    return 1
  fi
}

if [ "${1:-}" = --capture ]; then
  shift
  capture "$@"
  exit
fi

for tool in android-broker convert; do
  command -v "$tool" >/dev/null || { printf '%s is required\n' "$tool" >&2; exit 1; }
done
scenes=("$@")
[ "${#scenes[@]}" -gt 0 ] || scenes=("${all_scenes[@]}")
for scene in "${scenes[@]}"; do
  case " ${all_scenes[*]} " in
    *" $scene "*) ;;
    *) printf 'unknown scene: %s\n' "$scene" >&2; exit 1 ;;
  esac
done

./gradlew --console=plain -q assembleDebug
apk="$PWD/app/build/outputs/apk/debug/app-debug.apk"
raw="$(mktemp -d)"
trap 'rm -rf "$raw"' EXIT
android-broker run --device phone --api "${SCREENSHOT_API:-36}" --wait 900 -- \
  bash "$PWD/dev/screenshots.sh" --capture "$apk" "$raw" "${scenes[@]}"

out=docs/design/screenshots
for png in "$raw"/*.png; do
  convert "$png" -resize 540x960 -strip -colors 256 "PNG8:$out/$(basename "$png")"
done
printf 'saved %d screenshots in %s\n' "$(find "$raw" -name '*.png' | wc -l)" "$out"
