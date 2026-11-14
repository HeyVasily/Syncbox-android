#!/usr/bin/env bash
# Host-side Syncthing behaviour tests (engine/). Builds the test binary, then
# runs it in a loopback-only network namespace when unshare is available, so
# nothing can reach beyond 127.0.0.1.
#
# Usage: bash dev/test-engine.sh [go test flags, e.g. -test.run 'TestPaused']
#
# Heavier runs (see engine/README.md):
#   ENGINE_CYCLES=50                     start/stop cycles (default 20)
#   ENGINE_SCAN_FILES=100000             remote files for the scan test (default 2000)
#   ENGINE_SCAN_LINES=100,1000,5000      ignore line counts (default 100,1000)
#   ENGINE_FINISH_RUNS=7                 iterations of the finished-rule test
#   ENGINE_LOG=1                         Syncthing debug logs to stderr
#   ENGINE_NETNS=0                       do not use a network namespace
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/.build/engine"
mkdir -p "$OUT"
cd "$ROOT/engine"

go vet ./...
go vet -tags noassets ./...
go test -c -tags noassets -o "$OUT/engine.test" .

run=("$OUT/engine.test" -test.v -test.count=1 -test.timeout=60m "$@")
# The namespace needs unshare (unprivileged user namespaces) and ip (to bring
# up loopback inside it). Without either, fall back and say so.
if [ "${ENGINE_NETNS:-1}" = 0 ]; then
  printf 'network namespace disabled (ENGINE_NETNS=0)\n' >&2
elif ! command -v ip >/dev/null; then
  printf 'warning: ip not found; running without a network namespace (set ENGINE_NETNS=0 to silence)\n' >&2
elif ! unshare -rn ip link set lo up 2>/dev/null; then
  printf 'warning: unshare -rn not permitted; running without a network namespace (set ENGINE_NETNS=0 to silence)\n' >&2
else
  printf 'running in a loopback-only network namespace\n'
  # shellcheck disable=SC2016 # expanded by the inner shell
  run=(unshare -rn sh -c 'ip link set lo up && exec "$@"' sh "${run[@]}")
fi

start=$(date +%s)
status=0
"${run[@]}" 2>&1 | sed -u -e '/^=== \(RUN\|PAUSE\|CONT\)/d' | tee "$OUT/test.log" || status=$?
printf 'engine tests: exit %d after %ds (log: .build/engine/test.log)\n' "$status" "$(( $(date +%s) - start ))"
exit "$status"
