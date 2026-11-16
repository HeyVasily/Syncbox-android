#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# actionlint otherwise silently skips shell analysis when ShellCheck is absent.
command -v shellcheck >/dev/null || { printf 'workflow lint requires shellcheck\n' >&2; exit 1; }
actionlint="$(bash dev/pinned-tool.sh actionlint)"
zizmor="$(bash dev/pinned-tool.sh zizmor)"
shopt -s nullglob
workflows=(.github/workflows/*.yml .github/workflows/*.yaml)
status=0
"$actionlint" -color "${workflows[@]}" || status=$?
"$zizmor" --offline --strict-collection --no-progress .github || status=$?

# Secrets, release signing keys above all: only in jobs that use an environment (zizmor's
# auditor-level secrets-outside-env audit), and none at all in a workflow that pull requests
# trigger. GITHUB_TOKEN is not a secret here. The environment's protection is a repository
# setting (docs/ci.md).
findings="$(mktemp)"
trap 'rm -f -- "$findings"' EXIT
"$zizmor" --offline --strict-collection --no-progress --persona auditor --format json \
  --no-exit-codes .github > "$findings" 2>/dev/null || status=$?
python3 -I - "$findings" "${workflows[@]}" <<'PY' || status=$?
import json, re, sys

bad = 0
def error(path, line, message):
    global bad
    bad += 1
    print(f"::error file={path},line={line}::{message}")

for finding in json.load(open(sys.argv[1])):
    if finding["ident"] != "secrets-outside-env":
        continue
    for loc in finding["locations"]:
        if loc["symbolic"]["kind"] != "Primary":
            continue
        path = loc["symbolic"]["key"].get("Local", {}).get("verbatim_path", "?")
        row = loc["concrete"]["location"]["start_point"]["row"] + 1
        error(path, row, "a secret is used in a job without an environment")

def triggers(lines):
    for i, line in enumerate(lines):
        m = re.match(r"""^(?:on|"on"|'on'|true)\s*:(.*)$""", line)
        if not m:
            continue
        inline = m.group(1).split("#")[0].strip()
        if inline:
            return set(re.findall(r"[a-z_]+", inline))
        body = [l for l in lines[i + 1:] if l.strip() and not l.lstrip().startswith("#")]
        found = set()
        for l in body:
            if not l[0].isspace():
                break
            indent = len(body[0]) - len(body[0].lstrip())
            m = re.match(r"^ {%d}(?:-\s*)?([a-z_]+)" % indent, l)
            if m:
                found.add(m.group(1))
        return found
    return set()

secret = re.compile(r"\bsecrets\s*(?:\.\s*(\w+)|\[\s*['\"](\w+)['\"]\s*\])")
for path in sys.argv[2:]:
    lines = open(path, encoding="utf-8").read().splitlines()
    if not triggers(lines) & {"pull_request", "pull_request_target"}:
        continue
    for n, line in enumerate(lines, 1):
        if line.lstrip().startswith("#"):
            continue
        names = [a or b for expr in re.findall(r"\$\{\{(.*?)\}\}", line) for a, b in secret.findall(expr)]
        if any(name != "GITHUB_TOKEN" for name in names) or re.search(r"\bsecrets:\s*inherit\b", line):
            error(path, n, "a workflow that pull requests trigger must not use secrets")
        if re.match(r"^\s+environment:", line):
            error(path, n, "a workflow that pull requests trigger must not use an environment")
sys.exit(1 if bad else 0)
PY
mapfile -d '' scripts < <(git ls-files -z --cached --others --exclude-standard -- '*.sh')
shellcheck -x -S warning "${scripts[@]}" || status=$?
exit "$status"
