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
# Call analysis needs the Go toolchain and code that compiles, and fails the scan when it cannot
# run. Without it every advisory for a listed module version is reported.
args=(--no-call-analysis=go)
# Without --config the scanner looks for osv-scanner.toml next to each manifest, not in the root.
[ -f osv-scanner.toml ] && args+=(--config osv-scanner.toml)
for manifest in "${manifests[@]}"; do
  args+=(--lockfile "$manifest")
done
status=0
"$scanner" scan source "${args[@]}" || status=$?
# 128: the manifests list no packages, for example a go.mod without requirements.
if [ "$status" -eq 128 ]; then
  printf 'the manifests list no packages: nothing to audit\n'
  exit 0
fi
exit "$status"
