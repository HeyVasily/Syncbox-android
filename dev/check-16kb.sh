#!/usr/bin/env bash
# 16 KB page size checks for APKs: ZIP alignment of uncompressed libraries, and ELF LOAD
# alignment of every 64-bit library. Reports every violation.
# Usage: check-16kb.sh [apk...]   Default: every APK under */build/outputs/apk/.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ "$#" -gt 0 ]; then
  apks=("$@")
else
  cd "$ROOT"
  mapfile -d '' apks < <(find . -name .build -prune -o -type f -name '*.apk' \
    -path '*/build/outputs/apk/*' -print0 | sort -z)
fi
[ "${#apks[@]}" -gt 0 ] || { printf 'check-16kb: no APK to check\n' >&2; exit 1; }
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { printf 'check-16kb: ANDROID_HOME is not set\n' >&2; exit 1; }
# zipalign understands -P since Build Tools 35.
# Prefer a stable Build Tools release over a preview such as 37.0.0-rc2.
zipalign="$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name zipalign | sort -V |
  { grep -v -- '-rc' || true; } | tail -n 1)"
[ -n "$zipalign" ] || { printf 'check-16kb: no zipalign under %s/build-tools\n' "$sdk" >&2; exit 1; }
status=0
for apk in "${apks[@]}"; do
  printf '== %s\n' "$apk"
  "$zipalign" -c -P 16 4 "$apk" || {
    printf '::error file=%s::zipalign -c -P 16 failed\n' "$apk"
    status=1
  }
  python3 -I - "$apk" <<'PY' || status=1
import struct, sys, zipfile

PAGE = 16384
apk = sys.argv[1]
bad = checked = 0
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if not name.endswith(".so") or not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
            continue
        checked += 1
        data = z.read(name)
        problems = []
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
            print(f"::error file={apk}::{name}: {problem}")
        bad += bool(problems)
print(f"{checked} 64-bit libraries checked, {bad} failed")
sys.exit(1 if bad else 0)
PY
done
exit "$status"
