# CI

The repository is public. `.github/workflows/ci.yml` runs on every pull request and on every push to `master`. A new push to a pull request cancels the check of the previous one. Run the same checks locally with `bash dev/check.sh`; each check reports all violations, not only the first.

| Check | Script | What it catches |
|---|---|---|
| Secrets | `dev/check-secrets.sh` | gitleaks over the full git history and the uncommitted working tree |
| Repository rules | `dev/check-repo.sh` | signing keys, `local.properties` and similar files, APK/AAB files, files over 1 MiB (except `gradle/verification-metadata.xml`), Cyrillic text, development machine paths, missing dependency protections (below) |
| Workflows | `dev/lint-workflows.sh` | actionlint, zizmor and ShellCheck |
| Android | `dev/check-android.sh` | Android Lint, unit tests, a debug build, then `dev/check-16kb.sh` on the built APKs |

Tools are official release binaries pinned by version and SHA-256 in `dev/pinned-tool.sh`, cached in `.build/tools/`. Actions are pinned by commit SHA; Dependabot updates them weekly.

## Android

`dev/check-android.sh` does nothing until `gradlew` exists. From then on it runs `lint`, `testDebugUnitTest` and `assembleDebug` for all modules, and needs a JDK and `ANDROID_HOME`. CI also validates the Gradle wrapper and keeps the reports of a failed build for 7 days.

`dev/check-16kb.sh [apk...]` checks each APK with `zipalign -c -P 16`, and checks that every LOAD segment of every `arm64-v8a` and `x86_64` library is aligned to 16 KB. Background: [research/16kb-page-size.md](research/16kb-page-size.md).

## Dependencies

- **Updates:** Dependabot, configured in `.github/dependabot.yml`.
- **Known vulnerabilities:** `.github/workflows/dependency-audit.yml` runs `dev/audit-deps.sh` every day, and when a dependency manifest changes in a pull request or on `master`. It checks `go.mod`, Gradle lockfiles and `gradle/verification-metadata.xml` against the OSV database. Gradle dependencies are covered through the verification file, which every dependency change has to update. It is not part of `dev/check.sh`, because it needs the network and its result changes without any change in the repository. To accept an advisory, list it with a reason in `osv-scanner.toml` in the repository root. GitHub disables scheduled workflows in a public repository after 60 days without activity, and they have to be enabled again by hand.
- **Pull requests, second check:** the `dependency-review` job fails a pull request that adds a dependency with a known vulnerability of moderate or higher severity, in any scope. It sees only what GitHub reads from the repository itself: Go modules and Actions, not Gradle.
- **Protections that cannot be forgotten:** `dev/check-repo.sh` fails when `gradlew` exists without a pinned wrapper checksum (`distributionSha256Sum`), without Gradle dependency verification (`gradle/verification-metadata.xml`), without the `gradle` ecosystem in Dependabot, or outside the repository root. `dev/check-android.sh` builds with `--dependency-verification strict`, so `gradle.properties` cannot weaken verification. It also fails when a `go.mod` exists without the `gomod` ecosystem in Dependabot.

## Not covered yet

See the `android-ci-checks` task: 16 KB checks of APKs generated from the App Bundle, `govulncheck` on the Go bridge, instrumented tests on an emulator, the release workflow with signing keys, and the F-Droid free-software check.
