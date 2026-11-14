# CI

The repository is public. `.github/workflows/ci.yml` runs on every pull request and on every push to `master`. Run the same checks locally with `bash dev/check.sh`; each check reports all violations, not only the first.

| Check | Script | What it catches |
|---|---|---|
| Secrets | `dev/check-secrets.sh` | gitleaks over the full git history and the uncommitted working tree |
| Repository rules | `dev/check-repo.sh` | signing keys, `local.properties` and similar files, APK/AAB files, files over 1 MiB, Cyrillic text, development machine paths, missing dependency protections (below) |
| Workflows | `dev/lint-workflows.sh` | actionlint, zizmor and ShellCheck |
| Android | `dev/check-android.sh` | Android Lint, unit tests, a debug build, then `dev/check-16kb.sh` on the built APKs |

Tools are official release binaries pinned by version and SHA-256 in `dev/pinned-tool.sh`, cached in `.build/tools/`. Actions are pinned by commit SHA; Dependabot updates them weekly.

## Android

`dev/check-android.sh` does nothing until `gradlew` exists. From then on it runs `lint`, `testDebugUnitTest` and `assembleDebug` for all modules, and needs a JDK and `ANDROID_HOME`. CI also validates the Gradle wrapper and keeps the reports of a failed build for 7 days.

`dev/check-16kb.sh [apk...]` checks each APK with `zipalign -c -P 16`, and checks that every LOAD segment of every `arm64-v8a` and `x86_64` library is aligned to 16 KB. Background: [research/16kb-page-size.md](research/16kb-page-size.md).

## Dependencies

- **Updates:** Dependabot, configured in `.github/dependabot.yml`.
- **Pull requests:** the `dependency-review` job fails a pull request that adds a dependency with a known vulnerability of moderate or higher severity.
- **Known vulnerabilities:** `.github/workflows/dependency-audit.yml` runs `dev/audit-deps.sh` every day and when a dependency manifest changes on `master`. It checks `go.mod`, Gradle lockfiles and `gradle/verification-metadata.xml` against the OSV database. It is not part of `dev/check.sh`, because it needs the network and its result changes without any change in the repository.
- **Protections that cannot be forgotten:** `dev/check-repo.sh` fails when `gradlew` exists without a pinned wrapper checksum (`distributionSha256Sum`), without Gradle dependency verification (`gradle/verification-metadata.xml`), or without the `gradle` ecosystem in Dependabot. It also fails when a `go.mod` exists without the `gomod` ecosystem in Dependabot.

## Not covered yet

See the `android-ci-checks` task: 16 KB checks of APKs generated from the App Bundle, `govulncheck` on the Go bridge, instrumented tests on an emulator, the release workflow with signing keys, and the F-Droid free-software check.
