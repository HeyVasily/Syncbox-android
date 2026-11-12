#!/usr/bin/env bash
# All local CI checks. Runs every check and reports each failure.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
failed=()
for check in check-secrets check-repo lint-workflows; do
  printf '== %s\n' "$check"
  bash "dev/$check.sh" || failed+=("$check")
done
[ "${#failed[@]}" -eq 0 ] || { printf 'failed: %s\n' "${failed[*]}" >&2; exit 1; }
printf 'all checks passed\n'
