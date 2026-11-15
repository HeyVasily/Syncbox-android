#!/usr/bin/env bash
# End-to-end check on an emulator from android-broker: the debug app runs Syncthing in-process,
# pairs with a computer-side peer (Syncthing from the same pin, run on this host), accepts the
# peer's folder through the app's UI and lists the peer's files, and nothing is downloaded.
# Then: the app leaves the screen and Syncthing stops; offline mode keeps it stopped.
# See docs/verification.md.
#
# Usage: bash dev/e2e-emulator.sh [--screenshots]
#   --screenshots  also saves docs/design/screenshots/real-*.png (light and dark)
# Env: ANDROID_HOME, ANDROID_NDK_HOME (as for dev/build-app.sh); E2E_HOST_IP overrides the
#   host address the emulator dials (default: this host's source address for its default route);
#   E2E_API (default 36).
# Results: .build/e2e/result.txt; logs and raw screenshots in .build/e2e/.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/.build/e2e"
PKG=app.avoska
FOLDER_ID=e2e-media
FOLDER_LABEL="Test media"

# Files on the peer: name and size in bytes. Sizes are decimal so the app shows round values.
PEER_FILES=(
  "Big video.mkv:5000000"
  "Notes.pdf:200000"
  "a [b] {c} #d !e.txt:1000"
  "Caf$(printf '\xc3\xa9') menu.txt:10"
  "Series/Episode 01.mkv:2000000"
  "Series/Episode 02.mkv:3000000"
)

log() { printf '%s\n' "$*" | tee -a "$OUT/result.txt"; }
check() { log "CHECK ok: $*"; }
die() {
  log "CHECK FAILED: $*"
  # On the device, keep what the screen showed.
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    adb exec-out screencap -p >"$OUT/failure.png" 2>/dev/null || true
    adb logcat -d >"$OUT/logcat.txt" 2>/dev/null || true
  fi
  exit 1
}

# wait_for <seconds> <description> <command...>: retries the command every second.
wait_for() {
  local timeout=$1 what=$2 i
  shift 2
  for ((i = 0; i < timeout; i++)); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  die "timed out after ${timeout}s waiting for: $what"
}

peer() { # peer <method> <path> [json body]
  local args=(-fsS -X "$1" -H "X-API-Key: $PEER_KEY")
  [ $# -lt 3 ] || args+=(-H 'Content-Type: application/json' -d "$3")
  curl "${args[@]}" "http://$PEER_GUI$2"
}

peer_scanned() {
  peer GET "/rest/db/status?folder=$FOLDER_ID" | json "d['globalFiles'] == ${#PEER_FILES[@]} and d['state'] == 'idle'" | grep True >/dev/null
}

json() { python3 -I -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }

# ---------------------------------------------------------------- device part
# Runs inside android-broker run, which sets ANDROID_SERIAL and ADB_SERVER_SOCKET.

UI_XML="$OUT/ui.xml"

ui_dump() {
  local attempt xy
  for attempt in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
    adb exec-out cat /sdcard/ui.xml >"$UI_XML"
    grep -q "isn&apos;t responding\|isn't responding" "$UI_XML" || return 0
    # The loaded emulator sometimes shows "System UI isn't responding"; wait it out. The same
    # dialog for this app is a failure.
    if grep -q 'Avoska isn' "$UI_XML"; then die "the app is not responding (ANR dialog)"; fi
    log "dismissed a system 'isn't responding' dialog (attempt $attempt)"
    xy="$(ui center Wait)" || return 1
    # shellcheck disable=SC2086 # two numbers
    adb shell input tap $xy
    sleep 2
  done
  return 1
}

# ui <query> <arg>: queries the last dump. "center TEXT" prints the centre of the node whose text
# or content-desc is TEXT; "has TEXT" fails when there is none; "texts" prints all texts;
# "switch TEXT" prints the centre of the switch on the same row as TEXT; "checked TEXT" fails
# unless that switch is on.
ui() {
  python3 -I - "$UI_XML" "$@" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, query = sys.argv[1], sys.argv[2]
arg = sys.argv[3] if len(sys.argv) > 3 else ""
nodes = list(ET.parse(path).iter("node"))
def box(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return x1, y1, x2, y2
def named(t):
    return [n for n in nodes if t and (n.get("text") == t or n.get("content-desc") == t)]
if query == "texts":
    for n in nodes:
        for k in ("text", "content-desc"):
            if n.get(k):
                print(n.get(k))
    sys.exit(0)
hits = named(arg)
if not hits:
    sys.exit(1)
x1, y1, x2, y2 = box(hits[0])
if query == "has":
    sys.exit(0)
if query == "center":
    print((x1 + x2) // 2, (y1 + y2) // 2)
    sys.exit(0)
if query in ("switch", "checked"):
    cy = (y1 + y2) / 2
    switches = [n for n in nodes if n.get("checkable") == "true"]
    best = min(switches, key=lambda n: abs((box(n)[1] + box(n)[3]) / 2 - cy), default=None)
    if best is None or abs((box(best)[1] + box(best)[3]) / 2 - cy) > 120:
        sys.exit(1)
    if query == "checked":
        sys.exit(0 if best.get("checked") == "true" else 1)
    bx1, by1, bx2, by2 = box(best)
    print((bx1 + bx2) // 2, (by1 + by2) // 2)
    sys.exit(0)
sys.exit(2)
PY
}

ui_has() { ui_dump && ui has "$1"; }

# field_text: the text of the first input field in a fresh dump.
field_text() {
  ui_dump || return 1
  python3 -I - "$UI_XML" <<'PY'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter("node"):
    if n.get("class") == "android.widget.EditText":
        print(n.get("text") or "")
        break
PY
}

field_is() { [ "$(field_text)" = "$1" ]; }

# type_device_id <id>: types the ID without dashes (the app accepts that) in short chunks and
# checks the field after each one: the emulator's input text drops characters under load.
type_device_id() {
  local raw=${1//-/} attempt typed chunk xy i t
  for attempt in 1 2 3; do
    ui_dump
    xy="$(python3 -I - "$UI_XML" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter("node"):
    if n.get("class") == "android.widget.EditText":
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)"
    [ -n "$xy" ] || die "no device ID field"
    # shellcheck disable=SC2086 # two numbers
    adb shell input tap $xy
    sleep 2
    # Clear whatever a failed attempt left.
    if [ -n "$(field_text)" ]; then
      adb shell input keyevent KEYCODE_MOVE_END
      for ((i = 0; i < 70; i++)); do adb shell input keyevent KEYCODE_DEL; done
    fi
    typed=""
    for ((i = 0; i < ${#raw}; i += 8)); do
      chunk=${raw:i:8}
      adb shell input text "$chunk"
      typed+=$chunk
      for ((t = 0; t < 20; t++)); do field_is "$typed" && break; sleep 1; done
      field_is "$typed" || break
    done
    if field_is "$raw"; then
      check "typed the peer's device ID into the Add a device field (attempt $attempt)"
      return 0
    fi
    log "typing attempt $attempt left '$(field_text)'"
  done
  die "could not type the device ID"
}

tap() { # tap <text or content-desc>
  local xy
  { ui_dump && xy="$(ui center "$1")"; } || die "no element '$1' on screen"
  # shellcheck disable=SC2086 # two numbers
  adb shell input tap $xy
}

tap_switch() { # tap_switch <row text>
  local xy
  { ui_dump && xy="$(ui switch "$1")"; } || die "no switch next to '$1'"
  # shellcheck disable=SC2086
  adb shell input tap $xy
}

# phone <method> <path> [json body]: the phone's own REST API, called from adb shell on the device
# (the REST API listens on the device's loopback). The address and key are read from the debug
# app's private config with run-as, the way an advanced user would edit settings in Syncthing.
phone() {
  local cfg addr key body=${3:-} req
  cfg="$(adb shell run-as "$PKG" cat files/syncthing/config.xml)"
  addr="$(sed -n '/<gui /,/<\/gui>/s:.*<address>\(.*\)</address>.*:\1:p' <<<"$cfg" | head -1)"
  key="$(sed -n '/<gui /,/<\/gui>/s:.*<apikey>\(.*\)</apikey>.*:\1:p' <<<"$cfg" | head -1)"
  [ -n "$addr" ] && [ -n "$key" ] || return 1
  req="$OUT/request.http"
  printf '%s %s HTTP/1.0\r\nHost: %s\r\nX-API-Key: %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n%s' \
    "$1" "$2" "$addr" "$key" "${#body}" "$body" >"$req"
  adb push "$req" /data/local/tmp/avoska-request.http >/dev/null
  adb shell "nc -w 10 ${addr%:*} ${addr#*:} < /data/local/tmp/avoska-request.http" >"$OUT/response.http" || return 1
  head -1 "$OUT/response.http" | grep ' 200 ' >/dev/null || return 1
  sed '1,/^\r\{0,1\}$/d' "$OUT/response.http"
}

phone_rest_down() { ! phone GET /rest/noauth/health >/dev/null 2>&1; }

logcat_has() { adb logcat -d -s AvoskaEngine:I | grep "$1" >/dev/null; }
started_count() { adb logcat -d -s AvoskaEngine:I | grep -c 'Syncthing started' || true; }
stopped_count() { adb logcat -d -s AvoskaEngine:I | grep -c 'Syncthing stopped in' || true; }

peer_connected() { peer GET /rest/system/connections | json "d['connections'].get('$PHONE_ID', {}).get('connected', False)" | grep True >/dev/null; }
peer_disconnected() { ! peer_connected; }
phone_has_peer() { phone GET "/rest/config/devices/$PEER_ID"; }
stopped_since() { [ "$(stopped_count)" -gt "$1" ]; }
# stop_within <what> <t0 ns>: Syncthing must stop within STOP_LIMIT_MS of the trigger (the
# process lifecycle waits 700 ms before ON_STOP; Stop itself takes about 1 s).
STOP_LIMIT_MS=5000
stop_within() {
  local ms=$((($(date +%s%N) - $2) / 1000000))
  log "Syncthing stopped within ${ms} ms of $1 (limit ${STOP_LIMIT_MS} ms, polling included)"
  ((ms <= STOP_LIMIT_MS)) || die "Syncthing took ${ms} ms to stop after $1"
}
started_since() { [ "$(started_count)" -gt "$1" ]; }

screenshot() { # screenshot <name>: light and dark
  local theme
  for theme in light dark; do
    if [ "$theme" = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
    sleep 3
    adb exec-out screencap -p >"$OUT/screens/real-$1-$theme.png"
  done
  adb shell cmd uimode night no
  sleep 2
}

device_main() {
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
  log "device: $(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  log "device: ABI $(adb shell getprop ro.product.cpu.abi | tr -d '\r'), PAGE_SIZE $(adb shell getconf PAGE_SIZE | tr -d '\r'), kernel $(adb shell uname -r | tr -d '\r')"
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install -t "$APK" >/dev/null
  check "debug APK installed"
  adb shell settings put global sysui_demo_allowed 1
  local demo=(am broadcast -a com.android.systemui.demo -e command)
  adb shell "${demo[@]}" enter >/dev/null
  adb shell "${demo[@]}" clock -e hhmm 1200 >/dev/null
  adb shell "${demo[@]}" notifications -e visible false >/dev/null
  adb shell cmd uimode night no
  adb logcat -c

  # 1. Syncthing starts inside the installed app.
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  wait_for 60 "Syncthing started in the app (logcat)" logcat_has 'Syncthing started: device'
  PHONE_ID="$(adb logcat -d -s AvoskaEngine:I | sed -n 's/.*Syncthing started: device \([A-Z0-9-]*\),.*/\1/p' | head -1)"
  log "logcat: $(adb logcat -d -s AvoskaEngine:I | grep -m1 'Syncthing started' | sed 's/.*AvoskaEngine: //')"
  check "Syncthing started in-process, phone device ID $PHONE_ID"
  # libgojni.so is stored uncompressed and mapped straight from base.apk, so its name is not in
  # /proc/pid/maps: look for a base.apk mapping whose file offset lies inside the library's bytes
  # in the APK (GOJNI_RANGE, computed on the host).
  local pid maps
  pid="$(adb shell pidof "$PKG" | tr -d '\r')"
  adb shell run-as "$PKG" cat "/proc/$pid/maps" | tr -d '\r' >"$OUT/maps.txt"
  maps="$(python3 -I - "$OUT/maps.txt" "$GOJNI_RANGE" <<'PY'
import sys
start, end = map(int, sys.argv[2].split(":"))
for line in open(sys.argv[1]):
    f = line.split()
    if len(f) >= 6 and f[5].endswith("/base.apk") and start <= int(f[2], 16) < end:
        print(line.strip())
        break
PY
)"
  [ -n "$maps" ] || die "libgojni.so (base.apk bytes $GOJNI_RANGE) is not mapped in the app process"
  log "maps: $maps"
  check "libgojni.so mapped in the app process from base.apk (pid $pid)"

  # 2. The peer adds the phone and shares its folder with it.
  peer PUT "/rest/config/devices/$PHONE_ID" "{\"deviceID\":\"$PHONE_ID\",\"name\":\"e2e phone\",\"addresses\":[\"dynamic\"]}" >/dev/null
  peer PATCH "/rest/config/folders/$FOLDER_ID" "{\"devices\":[{\"deviceID\":\"$PEER_ID\"},{\"deviceID\":\"$PHONE_ID\"}]}" >/dev/null
  check "peer shares '$FOLDER_LABEL' with the phone"

  # 3. Pairing through the app's UI: first launch shows Add a device.
  wait_for 30 "Add a device screen" ui_has "Add device"
  type_device_id "$PEER_ID"
  # Enter is the field's Done action, which adds the device like the Add device button (the
  # keyboard may cover the button).
  adb shell input keyevent KEYCODE_ENTER
  wait_for 30 "phone config has the peer" phone_has_peer
  check "peer device added through the app UI (Add a device screen)"
  # The emulator reaches this host only by its own address; no discovery runs on the peer.
  phone PATCH "/rest/config/devices/$PEER_ID" "{\"addresses\":[\"tcp://$PEER_BEP\"]}" >/dev/null || die "could not set the peer address"
  check "static peer address tcp://$PEER_BEP set on the phone (adb shell, REST)"
  wait_for 90 "phone connected to the peer" peer_connected
  check "phone and peer connected"

  # 4. The folder offer appears and is accepted in the app.
  wait_for 60 "folder offer on screen" ui_has "Accept"
  ui_has "$FOLDER_LABEL" || die "the offer does not show the label '$FOLDER_LABEL'"
  check "folder offer shown on the Connect a folder screen"
  if [ "$SCREENSHOTS" = 1 ]; then screenshot folder-offer; fi
  tap "Accept"
  wait_for 60 "Files screen lists the peer's files" ui_has "Big video.mkv"
  sleep 3
  ui_dump
  local entry name size missing=0
  for entry in "${PEER_FILES[@]}"; do
    name="${entry%:*}"
    [[ "$name" == */* ]] && name="${name%%/*}"
    ui has "$name" || { log "missing on screen: $name"; missing=1; }
  done
  [ "$missing" = 0 ] || { ui texts >"$OUT/files-texts.txt"; die "Files screen does not list every peer entry (see .build/e2e/files-texts.txt)"; }
  ui texts >"$OUT/files-texts.txt"
  for size in "5 MB" "200 KB" "1 KB" "10 B"; do
    grep -q "^$size" "$OUT/files-texts.txt" || die "size $size not shown"
  done
  check "Files screen shows the peer's names (incl. special characters) and sizes 5 MB, 200 KB, 1 KB, 10 B, and the Series folder"
  grep -q 'Downloading is not available in this version yet' "$OUT/files-texts.txt" || die "Sync bar does not say downloads are unavailable"
  check "Sync is disabled with the reason 'Downloading is not available in this version yet'"
  if [ "$SCREENSHOTS" = 1 ]; then screenshot files; fi
  tap "Series"
  wait_for 20 "Series folder content" ui_has "Episode 01.mkv"
  ui has "Episode 02.mkv" || die "Episode 02.mkv missing"
  ui texts | grep '^2 MB' >/dev/null || die "Episode 01 size missing"
  check "subdirectory Series lists Episode 01.mkv (2 MB) and Episode 02.mkv (3 MB)"
  adb shell input keyevent KEYCODE_BACK
  sleep 2

  # 5. Accepting downloaded nothing; the folder is receive-only with the gate closed and `*` ignores.
  local folder ignores status completion
  folder="$(phone GET "/rest/config/folders/$FOLDER_ID")"
  json "d['type'], d['minDiskFree']" <<<"$folder" | tee -a "$OUT/result.txt" | grep "receiveonly.*1000000.*TB" >/dev/null || die "folder config: $folder"
  ignores="$(phone GET "/rest/db/ignores?folder=$FOLDER_ID")"
  json "d['ignore']" <<<"$ignores" | grep -x "\['\*'\]" >/dev/null || die "ignores: $ignores"
  check "phone folder: receive-only, minDiskFree 1000000 TB, ignores [*]"

  # Select one file: the intent is stored, nothing is armed or downloaded.
  ui_has "Notes.pdf" || die "Notes.pdf not on screen"
  tap_switch "Notes.pdf"
  sleep 2
  ui_dump
  ui checked "Notes.pdf" || die "Notes.pdf switch did not turn on"
  adb shell run-as "$PKG" cat files/selection.json | tee "$OUT/selection.json" | grep 'Notes.pdf' >/dev/null || die "selection not stored"
  check "selecting Notes.pdf stored the intent in files/selection.json: $(cat "$OUT/selection.json")"
  sleep 5
  status="$(phone GET "/rest/db/status?folder=$FOLDER_ID")"
  json "'globalFiles=%s globalBytes=%s localFiles=%s localBytes=%s needFiles=%s needBytes=%s state=%s' % (d['globalFiles'], d['globalBytes'], d['localFiles'], d['localBytes'], d['needFiles'], d['needBytes'], d['state'])" <<<"$status" | tee -a "$OUT/result.txt" >/dev/null
  json "d['globalFiles'] == 6 and d['localBytes'] == 0 and d['needBytes'] == 0" <<<"$status" | grep True >/dev/null || die "phone db status: $status"
  local files
  files="$(adb shell run-as "$PKG" find files/folders -type f | tr -d '\r')"
  log "files under files/folders: ${files//$'\n'/, }"
  [[ "$files" =~ ^files/folders/$FOLDER_ID-[0-9a-f]{12}/\.stignore$ ]] || die "unexpected files in the app's folder directory"
  check "nothing downloaded: phone has 6 global files, 0 local bytes, need 0; only .stignore on disk"
  completion="$(peer GET "/rest/db/completion?folder=$FOLDER_ID&device=$PHONE_ID")"
  log "peer's view of the phone: $(json "'completion=%s needItems=%s needBytes=%s' % (d['completion'], d['needItems'], d['needBytes'])" <<<"$completion")"
  json "d['completion'] == 100 and d['needBytes'] == 0" <<<"$completion" | grep True >/dev/null || die "peer completion: $completion"
  check "peer sees the phone at 100% with nothing needed"

  # 6. Leaving the screen stops Syncthing; coming back starts it again.
  local before t0
  before="$(stopped_count)"
  adb shell input keyevent KEYCODE_HOME
  t0=$(date +%s%N)
  wait_for 20 "Syncthing stopped after HOME" stopped_since "$before"
  stop_within "HOME" "$t0"
  log "logcat: $(adb logcat -d -s AvoskaEngine:I | grep 'Syncthing stopped in' | tail -1 | sed 's/.*AvoskaEngine: //')"
  phone_rest_down || die "REST still answers in the background"
  wait_for 30 "peer sees the phone disconnected" peer_disconnected
  adb shell pidof "$PKG" >/dev/null && log "app process still alive in the background (pid $(adb shell pidof "$PKG" | tr -d '\r')), Syncthing stopped"
  check "app in background: Syncthing stopped, REST API closed, peer sees the phone disconnected"
  before="$(started_count)"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  wait_for 30 "Syncthing started again" started_since "$before"
  wait_for 60 "reconnected" peer_connected
  check "app visible again: Syncthing restarted and reconnected"

  # 7. Offline mode: Syncthing stops and stays stopped.
  wait_for 20 "Files screen" ui_has "Settings"
  tap "Settings"
  wait_for 20 "Settings screen" ui_has "Offline mode"
  before="$(stopped_count)"
  tap_switch "Offline mode"
  t0=$(date +%s%N)
  wait_for 20 "Syncthing stopped in offline mode" stopped_since "$before"
  stop_within "offline mode" "$t0"
  sleep 3
  phone_rest_down || die "REST answers in offline mode"
  peer_connected && die "peer still connected in offline mode"
  adb shell input keyevent KEYCODE_BACK
  wait_for 20 "Files screen in offline mode" ui_has "Offline mode"
  check "offline mode: Syncthing stopped and stays stopped, Files status says Offline mode"

  # 8. Offline mode survives the process: a cold start stays offline.
  adb shell am force-stop "$PKG"
  before="$(started_count)"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  wait_for 20 "Files screen after a cold start" ui_has "Offline mode"
  sleep 5
  started_since "$before" && die "Syncthing started after a cold start in offline mode"
  check "offline mode survives a process restart: Syncthing not started, Files says Offline mode"

  if adb logcat -d -b crash | grep "$PKG" >/dev/null; then
    adb logcat -d -b crash | tee -a "$OUT/result.txt"
    die "the app crashed"
  fi
  check "no crash in logcat"
  adb logcat -d >"$OUT/logcat.txt"
  adb shell "${demo[@]}" exit >/dev/null
  log "E2E PASSED"
}

if [ "${1:-}" = --device ]; then
  device_main
  exit
fi

# ---------------------------------------------------------------- host part

SCREENSHOTS=0
[ "${1:-}" = --screenshots ] && SCREENSHOTS=1
for tool in android-broker curl python3 go; do
  command -v "$tool" >/dev/null || { printf '%s is required\n' "$tool" >&2; exit 1; }
done
[ "$SCREENSHOTS" = 0 ] || command -v convert >/dev/null || { printf 'convert (ImageMagick) is required for --screenshots\n' >&2; exit 1; }
rm -rf "$OUT"
mkdir -p "$OUT/screens"
start=$(date +%s)

HOST_IP="${E2E_HOST_IP:-$(ip -4 route get 1.1.1.1 | sed -n 's/.* src \([0-9.]*\).*/\1/p')}"
[ -n "$HOST_IP" ] || { printf 'could not find the host address; set E2E_HOST_IP\n' >&2; exit 1; }

cd "$ROOT"
./gradlew --console=plain -q assembleDebug
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
# Byte range of the x86_64 bridge library inside the APK, start:end, for the maps check.
GOJNI_RANGE="$(python3 -I - "$APK" <<'PY'
import struct, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z, open(sys.argv[1], "rb") as f:
    info = z.getinfo("lib/x86_64/libgojni.so")
    f.seek(info.header_offset)
    name_len, extra_len = struct.unpack("<HH", f.read(30)[26:30])
    start = info.header_offset + 30 + name_len + extra_len
    print(f"{start}:{start + info.compress_size}")
PY
)"
(cd engine && go test -c -tags noassets -o "$OUT/peer.test" .)

# The peer: the harness test binary in helper mode, i.e. stock Syncthing from the pinned module,
# with discovery, relays, NAT, usage and crash reporting off, listening only on HOST_IP.
free_port() { python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
PEER_HOME="$OUT/peer/home"
PEER_DIR="$OUT/peer/folder"
PEER_GUI="127.0.0.1:$(free_port)"
PEER_BEP="$HOST_IP:$(free_port)"
PEER_KEY="$(python3 -I -c 'import secrets; print(secrets.token_hex(16))')"
mkdir -p "$PEER_HOME" "$PEER_DIR/.stfolder"
for entry in "${PEER_FILES[@]}"; do
  name="${entry%:*}"
  mkdir -p "$(dirname "$PEER_DIR/$name")"
  head -c "${entry##*:}" /dev/urandom >"$PEER_DIR/$name"
done
cat >"$PEER_HOME/config.xml" <<EOF
<configuration version="52">
    <gui enabled="true" tls="false">
        <address>$PEER_GUI</address>
        <apikey>$PEER_KEY</apikey>
    </gui>
    <options>
        <listenAddress>tcp://$PEER_BEP</listenAddress>
        <globalAnnounceEnabled>false</globalAnnounceEnabled>
        <localAnnounceEnabled>false</localAnnounceEnabled>
        <relaysEnabled>false</relaysEnabled>
        <natEnabled>false</natEnabled>
        <urAccepted>-1</urAccepted>
        <crashReportingEnabled>false</crashReportingEnabled>
        <autoUpgradeIntervalH>0</autoUpgradeIntervalH>
        <startBrowser>false</startBrowser>
    </options>
</configuration>
EOF
ENGINE_HELPER_HOME="$PEER_HOME" "$OUT/peer.test" -test.run='^$' >"$OUT/peer.log" 2>&1 &
peer_pid=$!
trap 'kill "$peer_pid" 2>/dev/null || true' EXIT
wait_for 60 "peer REST API" peer GET /rest/system/ping
PEER_ID="$(peer GET /rest/system/status | json "d['myID']")"
peer PATCH "/rest/config/devices/$PEER_ID" '{"name":"Test computer"}' >/dev/null
peer PUT "/rest/config/folders/$FOLDER_ID" "{\"id\":\"$FOLDER_ID\",\"label\":\"$FOLDER_LABEL\",\"path\":\"$PEER_DIR\",\"type\":\"sendreceive\",\"fsWatcherEnabled\":false}" >/dev/null
wait_for 60 "peer scanned its folder" peer_scanned
log "peer: device $PEER_ID, listening on tcp://$PEER_BEP, folder '$FOLDER_LABEL' with ${#PEER_FILES[@]} files"

export APK GOJNI_RANGE PEER_ID PEER_KEY PEER_GUI PEER_BEP OUT SCREENSHOTS
android-broker run --device phone --api "${E2E_API:-36}" --wait 900 -- bash "$ROOT/dev/e2e-emulator.sh" --device

if [ "$SCREENSHOTS" = 1 ]; then
  for png in "$OUT"/screens/*.png; do
    convert "$png" -resize 540x960 -strip -colors 256 "PNG8:$ROOT/docs/design/screenshots/$(basename "$png")"
  done
  log "saved $(find "$OUT/screens" -name '*.png' | wc -l) screenshots in docs/design/screenshots"
fi
log "e2e: exit 0 after $(( $(date +%s) - start ))s"
