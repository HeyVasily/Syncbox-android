#!/usr/bin/env bash
# Known vulnerabilities in dependencies, from the OSV database. Needs network access.
# Does nothing until a dependency manifest exists.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mapfile -d '' manifests < <(git ls-files -z --cached --others --exclude-standard -- \
  ':(glob)**/go.mod' ':(glob)**/gradle.lockfile' ':(glob)**/buildscript-gradle.lockfile' \
  ':(glob)**/verification-metadata.xml')
if [ "${#manifests[@]}" -eq 0 ]; then
  printf 'no dependency manifests yet: audit skipped\n'
  exit 0
fi
scanner="$(bash dev/pinned-tool.sh osv-scanner)"
args=()
for manifest in "${manifests[@]}"; do
  args+=(--lockfile "$manifest")
done
"$scanner" scan source "${args[@]}"
