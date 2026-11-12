#!/usr/bin/env bash
# Public repository rules for tracked and untracked files. Reports every violation.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export LC_ALL=C.UTF-8
mapfile -d '' files < <(git ls-files -z --cached --others --exclude-standard)
status=0
fail() { printf '::error file=%s::%s\n' "$1" "$2"; status=1; }

for f in "${files[@]}"; do
  [ -f "$f" ] || continue
  case "${f##*/}" in
    *.jks|*.keystore|*.p12|*.pfx|*.pem|*.key|id_rsa*|id_ecdsa*|id_ed25519*|.env|.env.*|\
    local.properties|keystore.properties|signing.properties|google-services.json)
      fail "$f" "signing keys and local config must not be in the repository" ;;
    *.apk|*.aab|*.apks)
      fail "$f" "build outputs must not be in the repository" ;;
  esac
  if [ "$(stat -c %s "$f")" -gt 1048576 ]; then
    fail "$f" "file is larger than 1 MiB"
  fi
done

# Text checks: English only, and no paths from the development machine.
while IFS= read -r hit; do
  fail "${hit%%:*}" "non-English (Cyrillic) text: ${hit#*:}"
done < <(git grep -nIP --untracked '\p{Cyrillic}' -- . || true)
while IFS= read -r hit; do
  fail "${hit%%:*}" "development machine path: ${hit#*:}"
done < <(git grep -nIE --untracked "/srv/(workspaces)|/home/(dev)/" -- . || true)
exit "$status"
