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
  # On failure the Python check below reports the workflow as unreadable.
  "$yq" -o=json -I=0 'explode(.)' "${workflows[$i]}" > "$work/$i.json" || rm -f -- "$work/$i.json"
done
python3 -I - "$work" "${workflows[@]}" <<'PY' || status=$?
import json, os, re, sys

work, paths = sys.argv[1], sys.argv[2:]
bad = 0
def error(path, message):
    global bad
    bad += 1
    print(f"::error file={path}::{message}")

try:
    for finding in json.load(open(os.path.join(work, "zizmor.json"))):
        if finding["ident"] != "secrets-outside-env":
            continue
        for loc in finding["locations"]:
            if loc["symbolic"]["kind"] == "Primary":
                path = loc["symbolic"]["key"].get("Local", {}).get("verbatim_path", "?")
                row = loc["concrete"]["location"]["start_point"]["row"] + 1
                error(path, f"line {row}: a secret is used outside an environment (zizmor secrets-outside-env)")
except (OSError, ValueError, KeyError, TypeError, AttributeError) as e:
    error(".github", f"could not read zizmor's secrets-outside-env findings: {e!r}")

workflows = {}
for i, path in enumerate(paths):
    try:
        workflows[os.path.normpath(path)] = json.load(open(os.path.join(work, f"{i}.json")))
    except (OSError, ValueError) as e:
        error(path, f"not readable as one YAML document, so not checked for secrets: {e!r}")

def strip_literals(text):
    """Replaces each string literal ('...', where '' is a quote) with a placeholder \x00<n>\x00.
    Returns the code, the literals, and the index of the first }} outside a literal, or None."""
    code, literals, i = [], [], 0
    while i < len(text):
        if text[i] == "'":
            j, value = i + 1, []
            while j < len(text):
                if text.startswith("''", j):
                    value.append("'")
                    j += 2
                elif text[j] == "'":
                    break
                else:
                    value.append(text[j])
                    j += 1
            code.append(f"\x00{len(literals)}\x00")
            literals.append("".join(value))
            i = j + 1
        elif text.startswith("}}", i):
            return "".join(code), literals, i
        else:
            code.append(text[i])
            i += 1
    return "".join(code), literals, None

def expressions(text, whole):
    """The ${{ }} expressions in text as (code, literals); with whole, all of text is one."""
    if whole:
        code, literals, _ = strip_literals(text.replace("${{", "   "))
        return [(code.replace("}}", "  "), literals)]
    found, start = [], text.find("${{")
    while start >= 0:
        code, literals, end = strip_literals(text[start + 3:])
        found.append((code, literals))
        if end is None:  # unterminated: the rest of the text is the expression
            break
        start = text.find("${{", start + 3 + end + 2)
    return found

# Context names are case-insensitive. Every reference to the secrets context counts, also the
# bare object as in toJSON(secrets), except .GITHUB_TOKEN and ['GITHUB_TOKEN'].
SECRET = re.compile(r"(?<![\w.-])secrets\b(?:\s*\.\s*(\w+)|\s*\[\s*\x00(\d+)\x00\s*\])?", re.I)

def refers_to_secret(code, literals):
    for m in SECRET.finditer(code):
        name = m.group(1) or (literals[int(m.group(2))] if m.group(2) else "")
        if name.upper() != "GITHUB_TOKEN":
            return True
    return False

def uses_secret(node, key=None):
    """True if any expression in node refers to a secret other than GITHUB_TOKEN."""
    if isinstance(node, dict):
        return any(uses_secret(k, "key") or uses_secret(v, k) for k, v in node.items())
    if isinstance(node, list):
        return any(uses_secret(v, key) for v in node)
    if not isinstance(node, str):
        return False
    # An if condition is an expression, with or without ${{ }}.
    return any(refers_to_secret(*e) for e in expressions(node, whole=key == "if"))

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
