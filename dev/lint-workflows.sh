#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
zizmor="$(bash dev/pinned-tool.sh zizmor)"
shopt -s nullglob
status=0
if [ "${1:-}" = --self-test ]; then
  bad_fixtures=(dev/testdata/lint-workflows/bad/*.yml)
  good_fixtures=(dev/testdata/lint-workflows/good/*.yml)
  [ "${#bad_fixtures[@]}" -gt 0 ] && [ "${#good_fixtures[@]}" -gt 0 ] || exit 1
  workflows=("${bad_fixtures[@]}" "${good_fixtures[@]}")
  audit_inputs=("${workflows[@]}")
else
  # actionlint otherwise silently skips shell analysis when ShellCheck is absent.
  command -v shellcheck >/dev/null || { printf 'workflow lint requires shellcheck\n' >&2; exit 1; }
  actionlint="$(bash dev/pinned-tool.sh actionlint)"
  workflows=(.github/workflows/*.yml .github/workflows/*.yaml)
  audit_inputs=(.github)
  "$actionlint" -color "${workflows[@]}" || status=$?
  "$zizmor" --offline --strict-collection --no-progress .github || status=$?
fi

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
  --format json --no-exit-codes "${audit_inputs[@]}" > "$work/zizmor.json" || status=$?
for i in "${!workflows[@]}"; do
  # On failure the Python check below reports the workflow as unreadable.
  "$yq" -o=json -I=0 'explode(.)' "${workflows[$i]}" > "$work/$i.json" || rm -f -- "$work/$i.json"
done
python3 -I - "$work" "${1:-}" "${workflows[@]}" <<'PY' || status=$?
import json, os, re, sys

work, mode, *paths = sys.argv[1:]
bad = 0
rejected = set()
def error(path, message):
    global bad
    bad += 1
    print(f"::error file={path}::{message}")

def reject(path, message, rule="policy"):
    if mode == "--self-test" and os.path.basename(os.path.dirname(path)) == "bad":
        rejected.add((path, rule))
    else:
        error(path, message)

try:
    for finding in json.load(open(os.path.join(work, "zizmor.json"))):
        if finding["ident"] != "secrets-outside-env":
            continue
        for loc in finding["locations"]:
            if loc["symbolic"]["kind"] == "Primary":
                path = loc["symbolic"]["key"].get("Local", {}).get("verbatim_path", "?")
                row = loc["concrete"]["location"]["start_point"]["row"] + 1
                reject(path, f"line {row}: a secret is used outside an environment (zizmor secrets-outside-env)", "zizmor")
except (OSError, ValueError, KeyError, TypeError, AttributeError) as e:
    error(".github", f"could not read zizmor's secrets-outside-env findings: {e!r}")

workflows = {}
for i, path in enumerate(paths):
    try:
        workflows[os.path.normpath(path)] = json.load(open(os.path.join(work, f"{i}.json")))
    except (OSError, ValueError) as e:
        error(path, f"not readable as one YAML document, so not checked for secrets: {e!r}")

def uses_secret(node, condition=False):
    """Scan the whole string, including literals, when it can contain an expression."""
    if isinstance(node, dict):
        return any(uses_secret(k) or uses_secret(v) for k, v in node.items())
    if isinstance(node, list):
        return any(uses_secret(v) for v in node)
    if not isinstance(node, str) or (not condition and "${{" not in node):
        return False
    node = node.strip()
    if node.startswith("${{") and node.endswith("}}"):
        node = node[3:-2].strip()
    return bool(re.search(r"\bsecrets\b", node, re.I) and not re.fullmatch(
        r"secrets(?:\s*\.\s*github_token|\['github_token'\])", node, re.I
    ))

def job_uses_secret(job):
    for key, value in job.items():
        if uses_secret(key) or uses_secret(value, condition=key == "if"):
            return True
        if key == "steps" and isinstance(value, list):
            for step in value:
                if isinstance(step, dict) and uses_secret(step.get("if"), condition=True):
                    return True
    return False

def literal_environment(job):
    """The environment name if it is a non-empty literal string, else None. An expression could
    resolve to an empty name, and the job would then read repository secrets."""
    env = job.get("environment")
    if isinstance(env, dict):
        env = env.get("name")
    if isinstance(env, str) and env.strip() and "${{" not in env:
        return env
    return None

def triggers(w):
    on = w.get("on", w.get("true"))
    if isinstance(on, str):
        return {on}
    if isinstance(on, (list, dict)):
        return set(on)
    return set()

def jobs(w):
    """The jobs that are mappings; anything else is reported once below."""
    j = w.get("jobs") if isinstance(w, dict) else None
    return [(k, v) for k, v in j.items() if isinstance(v, dict)] if isinstance(j, dict) else []

# Workflows that run for a pull request: triggered by one, or called from one (transitively).
pr = [p for p, w in workflows.items() if isinstance(w, dict) and triggers(w) & {
    "pull_request", "pull_request_target", "pull_request_review", "pull_request_review_comment", "issue_comment"
}]
seen = set(pr)
while pr:
    path = pr.pop()
    for job_id, job in jobs(workflows[path]):
        where = f"jobs.{job_id}"
        if job_uses_secret(job) or "secrets" in job:
            reject(path, f"{where}: runs for pull requests and must not use secrets")
        if "environment" in job:
            reject(path, f"{where}: runs for pull requests and must not use an environment")
        called = job.get("uses")
        if isinstance(called, str):
            if not called.startswith("./"):
                reject(path, f"{where}: runs for pull requests and must not call a workflow from another repository")
                continue
            target = os.path.normpath(called.split("@")[0])
            if target not in workflows:
                reject(path, f"{where}: calls {called}, which is not a workflow here")
            elif target not in seen:
                seen.add(target)
                pr.append(target)

for path, w in workflows.items():
    if not isinstance(w, dict):
        error(path, "not a mapping, so not checked for secrets")
        continue
    if not isinstance(w.get("jobs"), dict):
        error(path, "jobs is not a mapping, so the jobs are not checked for secrets")
    for key, value in w.items():
        if key != "jobs" and uses_secret(value):
            reject(path, f"{key}: a secret at the workflow level reaches every job")
    for job_id, job in jobs(w):
        if "secrets" in job:
            reject(path, f"jobs.{job_id}: secrets must not be passed to a called workflow")
        elif job_uses_secret(job):
            if "environment" not in job:
                reject(path, f"jobs.{job_id}: a secret is used in a job without an environment")
            elif literal_environment(job) is None:
                reject(path, f"jobs.{job_id}: a job that uses a secret must name its environment as a non-empty literal, not an expression")

if mode == "--self-test":
    for path in paths:
        if os.path.basename(os.path.dirname(path)) == "bad":
            rule = "zizmor" if path.endswith("/zizmor-ignore.yml") else "policy"
            if (path, rule) not in rejected:
                error(path, f"expected rejection from {rule}")
sys.exit(1 if bad else 0)
PY
[ "${1:-}" != --self-test ] || exit "$status"
mapfile -d '' scripts < <(git ls-files -z --cached --others --exclude-standard -- '*.sh')
shellcheck -x -S warning "${scripts[@]}" || status=$?
exit "$status"
