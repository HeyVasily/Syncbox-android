#!/usr/bin/env bash
# Scan git history and the uncommitted working tree for secrets.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
gitleaks="$(bash dev/pinned-tool.sh gitleaks)"
status=0
"$gitleaks" git --no-banner --redact --exit-code 1 . || status=1
# Agents do not commit, so also scan tracked and untracked files as they are on disk.
tree="$(mktemp -d)"
trap 'rm -rf -- "$tree"' EXIT
git ls-files -z --cached --others --exclude-standard | while IFS= read -r -d '' f; do
  [ -f "$f" ] && printf '%s\0' "$f"
done | tar --null -T - -cf - | tar -xf - -C "$tree"
"$gitleaks" dir --no-banner --redact --exit-code 1 "$tree" || status=1
exit "$status"
