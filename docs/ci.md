# CI

The repository is public. `.github/workflows/ci.yml` runs on every pull request and on every push to `master`. A new push to a pull request cancels the check of the previous one. Run the same checks locally with `bash dev/check.sh`; each check reports all violations, not only the first.

| Check | Script | What it catches |
|---|---|---|
| Secrets | `dev/check-secrets.sh` | gitleaks over the full git history and the uncommitted working tree |
| Repository rules | `dev/check-repo.sh` | signing keys, `local.properties` and similar files, APK/AAB files, files over 1 MiB (except `gradle/verification-metadata.xml`), Cyrillic text, development machine paths, missing dependency protections (below) |
| Workflows | `dev/lint-workflows.sh` | actionlint, zizmor and ShellCheck; secrets outside an environment or in a pull request workflow (below) |
| Android | `dev/check-android.sh` | Android Lint, unit tests, a debug build, the release App Bundle, the instrumented test APK (compiled only), then `dev/check-16kb.sh` |
| CodeQL | `.github/workflows/codeql.yml` | code scanning of Kotlin, Go and the workflows; not part of `dev/check.sh` |
| Emulator | `.github/workflows/emulator.yml` | instrumented tests on API 34 (4 KB pages) and API 36 (16 KB pages); not part of `dev/check.sh` |

Tools (actionlint, zizmor, gitleaks, OSV-Scanner, yq, bundletool) are official release binaries, for bundletool its release JAR, pinned by version and SHA-256 in `dev/pinned-tool.sh`, cached in `.build/tools/`. Actions are pinned by commit SHA; Dependabot updates them weekly.

## Android

`dev/check-android.sh` does nothing until `gradlew` exists. From then on it runs `lint`, `testDebugUnitTest`, `assembleDebug`, `bundleRelease` and `assembleDebugAndroidTest` for all modules, and needs a JDK and `ANDROID_HOME`. CI also validates the Gradle wrapper and keeps the reports of a failed build for 7 days.

The app build compiles the Syncthing bridge from `engine/` (`dev/build-bridge.sh`), so it also needs Go and NDK 30.0.16248370 in `ANDROID_NDK_HOME`. GitHub runners ship an older NDK; in CI `dev/install-ndk.sh` installs the expected one with `sdkmanager`.

## CodeQL

`.github/workflows/codeql.yml` is an advanced setup: GitHub's default setup builds Kotlin without a way to install Go and the NDK first. It runs on pull requests, on pushes to `master` and weekly. The `master` ruleset requires its results. The default setup has to stay disabled in the repository settings, because GitHub rejects advanced-setup results while it is enabled.

## 16 KB pages

`dev/check-16kb.sh [apk|aab...]` checks every APK under `*/build/outputs/apk/` (except the androidTest APK) and every App Bundle under `*/build/outputs/bundle/`, or the files it is given. A bundle is checked through the split APKs that bundletool generates from it, as Play would serve them, after `bundletool validate`; its config must store libraries uncompressed with `PAGE_ALIGNMENT_16K`. In each APK it checks:

- `zipalign -c -P 16`;
- every library is built for the CPU of its ABI directory (ELF `e_machine`: AArch64, x86-64, ARM, 386);
- every `arm64-v8a` and `x86_64` library is stored uncompressed, and every LOAD segment is aligned to 16 KB;
- each of `arm64-v8a` and `x86_64` has the Syncthing bridge, `libgojni.so` (for a bundle: in one of its splits).

`dev/check-android.sh` passes it the debug APK and the release bundle by path, so a missing output fails the check.

At runtime the emulator workflow checks the app on a 16 KB device. Background: [research/16kb-page-size.md](research/16kb-page-size.md).

## Emulator

`.github/workflows/emulator.yml` runs the instrumented tests (`app/src/androidTest`) with `dev/instrumented-tests.sh --device` on GitHub's hosted runners, which have KVM, in two jobs: API 34 (minSdk) with the standard image, and API 36 (targetSdk) with the `google_apis_ps16k` image, whose page size is 16 KB. The script checks the page size the job expects, installs the debug app and its test APK fresh, runs them with `am instrument`, and fails on a failed test or an app crash in logcat. The tests start the real Syncthing through the bridge, and check that an executable mapping of `base.apk` lies inside the bytes of `libgojni.so` in the APK, so the library runs from the APK in place. The script refuses to run the tests over an existing install, and keeps logcat and the crash log in `.build/instrumented/` on every exit. On 16 KB pages the script also fails unless `dumpsys package` shows `pageSizeCompat=0`, that is, Android runs the app without page size compatibility mode; as a control, a copy of the APK with its libraries moved to 4 KB offsets must get a nonzero value. It does not use Gradle's `connectedDebugAndroidTest`, which downloads the Unified Test Platform outside `gradle/verification-metadata.xml`.

Each job takes 5 to 7 minutes. The workflow runs weekly, on demand (`workflow_dispatch`), on pushes to `master`, and on every push to a pull request whose changes include the app (`app/build.gradle.kts`, `app/src/main`, `app/src/debug`, `app/src/androidTest`), the bridge (`engine/`), the Gradle build (`gradlew`, root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/`, `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`), the build scripts (`dev/build-app.sh`, `dev/build-bridge.sh`, `dev/install-ndk.sh`), the workflow or `dev/instrumented-tests.sh`; not for changes to docs or other checks alone. A new push to a pull request cancels its running check; other runs are never cancelled. Locally, `bash dev/instrumented-tests.sh` builds and runs the same tests on an emulator from android-broker (`E2E_API`, default 36; the broker has no 16 KB image).

## Secrets and release signing

There is no release workflow yet. When one is added, its signing keys are secrets of a GitHub environment (for example `release`) that allows only `master` or release tags and needs a reviewer; that protection is a repository setting that no check here can see. `dev/lint-workflows.sh` enforces the part in the files. It reads the workflows as YAML with the pinned `yq`, so quoted keys, anchors and multiline expressions are seen as GitHub sees them, and looks for the word `secrets` in any case across each string containing `${{` and every job and step `if` condition. This includes occurrences inside string literals. A value or condition consisting solely of `secrets.GITHUB_TOKEN` or `secrets['GITHUB_TOKEN']` is exempt. A workflow that is not one readable YAML document, or zizmor output that cannot be read, is an error of its own; the other rules still run.

- A secret is used only inside a job that names its `environment` as a non-empty literal, in the string form or as `name:`; an expression could resolve to an empty name, and the job would then read repository secrets. Not in workflow-level `env` or any other workflow-level key.
- No job passes secrets to a called workflow, neither `secrets: inherit` nor a `secrets` mapping (a calling job cannot name an environment).
- A workflow that `pull_request`, `pull_request_target`, `pull_request_review`, `pull_request_review_comment` or `issue_comment` triggers, and every local workflow it calls, uses no secret and no `environment`, and calls no workflow from another repository.
- Second opinion: zizmor's auditor-level `secrets-outside-env` audit, with its configuration and ignore comments switched off (`--no-config --no-ignores`).

## Dependencies

- **Updates:** Dependabot, configured in `.github/dependabot.yml`.
- **Known vulnerabilities:** `.github/workflows/dependency-audit.yml` runs `dev/audit-deps.sh` every day and when a dependency manifest or bridge source file changes in a pull request or on `master`. OSV-Scanner checks `go.mod`, Gradle lockfiles and `gradle/verification-metadata.xml` against the OSV database. The audit also runs pinned `govulncheck` with the `noassets` build tag and the Go version from `engine/go.mod`. It fails on reachable vulnerabilities in Go modules or the standard library. Gradle dependencies are covered through the verification file, which every dependency change has to update. The audit is not part of `dev/check.sh`, because it needs the network and its result changes without any change in the repository. To accept an OSV-Scanner advisory, list it with a reason in `osv-scanner.toml` in the repository root. GitHub disables scheduled workflows in a public repository after 60 days without activity, and they have to be enabled again by hand.
- **Pull requests, second check:** the `dependency-review` job fails a pull request that adds a dependency with a known vulnerability of moderate or higher severity, in any scope. It sees only what GitHub reads from the repository itself: Go modules and Actions, not Gradle.
- **Protections that cannot be forgotten:** `dev/check-repo.sh` fails when `gradlew` exists without a pinned wrapper checksum (`distributionSha256Sum`), without Gradle dependency verification (`gradle/verification-metadata.xml`), without the `gradle` ecosystem in Dependabot, or outside the repository root. `dev/check-android.sh` builds with `--dependency-verification strict`, so `gradle.properties` cannot weaken verification. `dev/check-repo.sh` also fails when a `go.mod` exists without the `gomod` ecosystem in Dependabot. In `gradle/verification-metadata.xml`, the Kotlin plugin marker POMs are trusted in both official copies (`also-trust`): they differ between Maven Central and the Gradle Plugin Portal, and Gradle reads the portal when Maven Central does not answer. Regenerate both Plugin Portal hashes on every Kotlin update.

## Not covered yet

See the `android-ci-checks` task for the release workflow with signing keys and the F-Droid free-software check. The end-to-end check with a Syncthing peer (`dev/e2e-emulator.sh`) runs only locally.
