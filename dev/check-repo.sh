#!/usr/bin/env bash
# Public repository rules and dependency protections for tracked and untracked files.
# Reports every violation.
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
  # The Gradle verification file is required below and grows with the dependency tree.
  if [ "$f" != gradle/verification-metadata.xml ] && [ "$(stat -c %s "$f")" -gt 1048576 ]; then
    fail "$f" "file is larger than 1 MiB"
  fi
done

# Dependency protections must be switched on in the same change that adds the build files.
has() { [ -f "$1" ] && grep -qE -- "$2" "$1"; }
dependabot() {
  has .github/dependabot.yml "^[[:space:]]*-?[[:space:]]*package-ecosystem:[[:space:]]*[\"']?$1[\"']?[[:space:]]*(#.*)?\$" ||
    fail .github/dependabot.yml "Dependabot must cover the $1 ecosystem"
}
listing="$(printf '%s\n' "${files[@]}")"
tracked() { grep -qE -- "$1" <<< "$listing"; }
if tracked '/gradlew$'; then
  fail gradlew "the Gradle wrapper must be in the repository root"
fi
if tracked '^gradlew$'; then
  has gradle/wrapper/gradle-wrapper.properties '^distributionSha256Sum=' ||
    fail gradle/wrapper/gradle-wrapper.properties "the Gradle wrapper must pin distributionSha256Sum"
  has gradle/verification-metadata.xml '<verify-metadata>true</verify-metadata>' ||
    fail gradle/verification-metadata.xml "Gradle dependency verification must be on"
  dependabot gradle
fi
if tracked '(^|/)go\.mod$'; then
  dependabot gomod
fi

# Text checks: English only, and no paths from the development machine.
while IFS= read -r hit; do
  fail "${hit%%:*}" "non-English (Cyrillic) text: ${hit#*:}"
done < <(git grep -nIP --untracked '\p{Cyrillic}' -- . || true)
while IFS= read -r hit; do
  fail "${hit%%:*}" "development machine path: ${hit#*:}"
done < <(git grep -nIE --untracked "/srv/(workspaces)|/home/(dev)/" -- . || true)
exit "$status"
