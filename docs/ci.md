# CI

The repository is public. `.github/workflows/ci.yml` runs on every push and pull request. Run the same checks locally with `bash dev/check.sh`; each check reports all violations, not only the first.

| Check | Script | What it catches |
|---|---|---|
| Secrets | `dev/check-secrets.sh` | gitleaks over the full git history and the uncommitted working tree |
| Repository rules | `dev/check-repo.sh` | signing keys, `local.properties` and similar files, APK/AAB files, files over 1 MiB, Cyrillic text, development machine paths |
| Workflows | `dev/lint-workflows.sh` | actionlint, zizmor and ShellCheck |

Tools are official release binaries pinned by version and SHA-256 in `dev/pinned-tool.sh`, cached in `.build/tools/`. Actions are pinned by commit SHA; Dependabot updates them weekly.

Android build checks are added with the Gradle project: see the `android-ci-checks` task.
