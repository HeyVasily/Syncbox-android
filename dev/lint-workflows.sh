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

# Secrets, release signing keys above all (docs/ci.md): GITHUB_TOKEN aside, a secret is used only
# inside a job that names an environment; never at the workflow level, never passed to a called
# workflow; and a workflow that pull requests trigger, or a local workflow it calls, uses no
# secret and no environment and calls no workflow from another repository. The workflows are read
# as YAML (yq), so quoting and anchors do not hide anything. zizmor's auditor-level
# secrets-outside-env audit is a second opinion, with ignore comments and configuration off.
yq="$(bash dev/pinned-tool.sh yq)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
"$zizmor" --offline --strict-collection --no-progress --no-config --no-ignores --persona auditor \
  --format json --no-exit-codes .github > "$work/zizmor.json" || status=$?
for i in "${!workflows[@]}"; do
  "$yq" -o=json -I=0 'explode(.)' "${workflows[$i]}" > "$work/$i.json" ||
    { printf '::error file=%s::not readable as YAML\n' "${workflows[$i]}"; status=1; }
done
python3 -I - "$work" "${workflows[@]}" <<'PY' || status=$?
import json, os, re, sys

work, paths = sys.argv[1], sys.argv[2:]
bad = 0
def error(path, message):
    global bad
    bad += 1
    print(f"::error file={path}::{message}")

for finding in json.load(open(os.path.join(work, "zizmor.json"))):
    if finding["ident"] != "secrets-outside-env":
        continue
    for loc in finding["locations"]:
        if loc["symbolic"]["kind"] == "Primary":
            path = loc["symbolic"]["key"].get("Local", {}).get("verbatim_path", "?")
            row = loc["concrete"]["location"]["start_point"]["row"] + 1
            error(path, f"line {row}: a secret is used outside an environment (zizmor secrets-outside-env)")

workflows = {}
for i, path in enumerate(paths):
    try:
        workflows[os.path.normpath(path)] = json.load(open(os.path.join(work, f"{i}.json")))
    except (OSError, ValueError):
        pass  # reported by the shell loop

EXPR = re.compile(r"\$\{\{(.*?)\}\}", re.S)
SECRET = re.compile(r"(?<![\w.-])secrets\b")
TOKEN = re.compile(r"""(?<![\w.-])secrets\s*(?:\.\s*GITHUB_TOKEN\b|\[\s*['"]GITHUB_TOKEN['"]\s*\])""")

def uses_secret(node, key=None):
    """True if any expression in node refers to a secret other than GITHUB_TOKEN."""
    if isinstance(node, dict):
        return any(uses_secret(k, "key") or uses_secret(v, k) for k, v in node.items())
    if isinstance(node, list):
        return any(uses_secret(v, key) for v in node)
    if not isinstance(node, str):
        return False
    exprs = EXPR.findall(node)
    if key == "if":  # an if condition is an expression without ${{ }}
        exprs.append(node)
    return any(len(SECRET.findall(e)) > len(TOKEN.findall(e)) for e in exprs)

def triggers(w):
    on = w.get("on", w.get("true"))
    if isinstance(on, str):
        return {on}
    if isinstance(on, (list, dict)):
        return set(on)
    return set()

def jobs(w):
    return [(k, v) for k, v in (w.get("jobs") or {}).items() if isinstance(v, dict)]

# Workflows that run for a pull request: triggered by one, or called from one (transitively).
pr = [p for p, w in workflows.items()
      if isinstance(w, dict) and triggers(w) & {"pull_request", "pull_request_target"}]
seen = set(pr)
while pr:
    path = pr.pop()
    for job_id, job in jobs(workflows[path]):
        where = f"jobs.{job_id}"
        if uses_secret(job) or "secrets" in job:
            error(path, f"{where}: runs for pull requests and must not use secrets")
        if "environment" in job:
            error(path, f"{where}: runs for pull requests and must not use an environment")
        called = job.get("uses")
        if isinstance(called, str):
            if not called.startswith("./"):
                error(path, f"{where}: runs for pull requests and must not call a workflow from another repository")
                continue
            target = os.path.normpath(called.split("@")[0])
            if target not in workflows:
                error(path, f"{where}: calls {called}, which is not a workflow here")
            elif target not in seen:
                seen.add(target)
                pr.append(target)

for path, w in workflows.items():
    if not isinstance(w, dict):
        continue
    for key, value in w.items():
        if key != "jobs" and uses_secret(value):
            error(path, f"{key}: a secret at the workflow level reaches every job")
    for job_id, job in jobs(w):
        if "secrets" in job:
            error(path, f"jobs.{job_id}: secrets must not be passed to a called workflow")
        elif uses_secret(job) and "environment" not in job:
            error(path, f"jobs.{job_id}: a secret is used in a job without an environment")
sys.exit(1 if bad else 0)
PY
mapfile -d '' scripts < <(git ls-files -z --cached --others --exclude-standard -- '*.sh')
shellcheck -x -S warning "${scripts[@]}" || status=$?
exit "$status"
