#!/usr/bin/env bash
# 16 KB page size checks for APKs and App Bundles. Reports every violation.
# Each APK, and the set of APKs that bundletool generates from each App Bundle, is checked for:
# - ZIP alignment of uncompressed files (zipalign -c -P 16), in every APK;
# - every library built for the CPU of its ABI directory (ELF e_machine);
# - every 64-bit library (arm64-v8a, x86_64) stored uncompressed, with every ELF LOAD segment
#   aligned to 16 KB;
# - the Syncthing bridge (libgojni.so) in each of arm64-v8a and x86_64, because it is built for
#   both;
# - for a bundle: bundletool validate, and 16 KB alignment of uncompressed libraries in its config.
# Usage: check-16kb.sh [apk|aab...]
#   Default: every APK under */build/outputs/apk/ (except androidTest APKs, which have no
#   libraries) and every App Bundle under */build/outputs/bundle/.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ "$#" -gt 0 ]; then
  inputs=("$@")
else
  cd "$ROOT"
  mapfile -d '' inputs < <(find . -name .build -prune -o -type f \
    \( -name '*.apk' -path '*/build/outputs/apk/*' ! -path '*/build/outputs/apk/androidTest/*' \
    -o -name '*.aab' -path '*/build/outputs/bundle/*' \) -print0 | sort -z)
fi
[ "${#inputs[@]}" -gt 0 ] || { printf 'check-16kb: no APK or App Bundle to check\n' >&2; exit 1; }
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { printf 'check-16kb: ANDROID_HOME is not set\n' >&2; exit 1; }
# zipalign understands -P since Build Tools 35.
# Prefer a stable Build Tools release over a preview such as 37.0.0-rc2.
all="$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name zipalign | sort -V)"
zipalign="$(grep -v -- '/build-tools/[^/]*-rc[^/]*/zipalign$' <<< "$all" | tail -n 1 || true)"
[ -n "$zipalign" ] || zipalign="$(tail -n 1 <<< "$all")"
[ -n "$zipalign" ] || { printf 'check-16kb: no zipalign under %s/build-tools\n' "$sdk" >&2; exit 1; }
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
bundletool=

status=0
error() { printf '::error file=%s::%s\n' "$1" "$2"; status=1; }

# check_apks <label> <apk...>: the APKs are one installable unit (one APK, or a bundle's splits).
check_apks() {
  local label=$1 apk
  shift
  for apk in "$@"; do
    "$zipalign" -c -P 16 4 "$apk" || error "$label" "zipalign -c -P 16 failed: ${apk##*/}"
  done
  python3 -I - "$label" "$@" <<'PY' || status=1
import struct, sys, zipfile

PAGE = 16384
ABIS = ("arm64-v8a", "x86_64")
# ELF e_machine of each ABI directory: a library built for another CPU fails to load.
MACHINES = {"arm64-v8a": (183, "AArch64"), "x86_64": (62, "x86-64"), "armeabi-v7a": (40, "ARM"), "x86": (3, "386")}
BRIDGE = "libgojni.so"
label, apks = sys.argv[1], sys.argv[2:]
bad = checked = 0
found = set()
for apk in apks:
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            name = info.filename
            abi = name.split("/")[1] if name.count("/") == 2 else ""
            if not name.endswith(".so") or not name.startswith("lib/") or abi not in MACHINES:
                continue
            data = z.read(info)
            problems = []
            machine, cpu = MACHINES[abi]
            if data[:4] != b"\x7fELF" or len(data) < 0x14:
                problems.append("not an ELF file")
            else:
                actual, = struct.unpack_from("<H", data, 0x12)
                if actual != machine:
                    problems.append(f"built for ELF machine {actual}, not {cpu} ({machine}) as {abi} needs")
                elif abi in ABIS and name.rsplit("/", 1)[1] == BRIDGE:
                    found.add(abi)
            where = name if len(apks) == 1 else f"{apk.rsplit('/', 1)[-1]}: {name}"
            if abi not in ABIS:
                for problem in problems:
                    print(f"::error file={label}::{where}: {problem}")
                bad += bool(problems)
                continue
            checked += 1
            if info.compress_type != zipfile.ZIP_STORED:
                problems.append("compressed; libraries must be stored uncompressed (useLegacyPackaging = false)")
            if data[:6] != b"\x7fELF\x02\x01":
                problems.append("not a little-endian 64-bit ELF file")
            else:
                try:
                    phoff, = struct.unpack_from("<Q", data, 0x20)
                    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
                    loads = 0
                    for i in range(phnum):
                        p_type, _, offset, vaddr, _, _, _, align = struct.unpack_from(
                            "<IIQQQQQQ", data, phoff + i * phentsize)
                        if p_type != 1:
                            continue
                        loads += 1
                        if align < PAGE or align % PAGE:
                            problems.append(f"LOAD {loads} is aligned to {align:#x}, need a multiple of 0x4000")
                        elif offset % PAGE != vaddr % PAGE:
                            problems.append(f"LOAD {loads} offset {offset:#x} and address {vaddr:#x} differ modulo 0x4000")
                    if not loads:
                        problems.append("no LOAD segment")
                except (struct.error, IndexError):
                    problems.append("truncated or malformed ELF file")
            for problem in problems:
                print(f"::error file={label}::{where}: {problem}")
            bad += bool(problems)
for abi in ABIS:
    if abi not in found:
        print(f"::error file={label}::no lib/{abi}/{BRIDGE}; the Syncthing bridge must be in every 64-bit ABI")
        bad += 1
print(f"{checked} 64-bit libraries in {len(apks)} APK(s) checked, {bad} problem(s)")
sys.exit(1 if bad else 0)
PY
}

for input in "${inputs[@]}"; do
  printf '== %s\n' "$input"
  [ -f "$input" ] || { error "$input" "no such file"; continue; }
  case "$input" in
    *.apk) check_apks "$input" "$input" ;;
    *.aab)
      if [ -z "$bundletool" ]; then
        bundletool="$(bash "$ROOT/dev/pinned-tool.sh" bundletool)" || { error "$input" "bundletool is not available"; continue; }
      fi
      java -jar "$bundletool" validate --bundle="$input" > /dev/null || error "$input" "bundletool validate failed"
      config="$(java -jar "$bundletool" dump config --bundle="$input")" || { error "$input" "bundletool dump config failed"; continue; }
      python3 -I -c '
import json, sys
u = json.loads(sys.argv[1]).get("optimizations", {}).get("uncompressNativeLibraries", {})
sys.exit(0 if u.get("enabled") and u.get("alignment") == "PAGE_ALIGNMENT_16K" else 1)' "$config" ||
        error "$input" "bundle config must store libraries uncompressed with PAGE_ALIGNMENT_16K"
      # The split APKs Play would serve, for every ABI and screen. Signed with the debug key when
      # there is one; signing does not change the alignment.
      out="$tmp/$(basename "$input" .aab)-$RANDOM"
      mkdir -p "$out"
      if java -jar "$bundletool" build-apks --bundle="$input" --output="$out/set.apks" > /dev/null &&
        unzip -q "$out/set.apks" -d "$out/set"; then
        mapfile -d '' splits < <(find "$out/set" -type f -name '*.apk' -print0 | sort -z)
        if [ "${#splits[@]}" -eq 0 ]; then
          error "$input" "bundletool generated no APK"
        else
          check_apks "$input" "${splits[@]}"
        fi
      else
        error "$input" "bundletool build-apks failed"
      fi
      ;;
    *) error "$input" "not an APK or App Bundle" ;;
  esac
done
exit "$status"
