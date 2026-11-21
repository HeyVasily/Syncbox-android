---
title: Add Android build checks to CI
assignee: agent
depends_on: []
touches:
  - .github/
  - dev/
  - docs/ci.md
  - docs/verification.md
  - app/build.gradle.kts
  - app/src/androidTest/
  - gradle/
---

## Goal
CI builds and checks the app once the Gradle project exists.

## Steps
- [x] Gradle wrapper validation (`gradle/actions/wrapper-validation`, pinned by SHA) in `ci.yml`.
- [x] Debug build, Android Lint and unit tests on every pull request and on `master`: `dev/check-android.sh`.
- [x] 16 KB checks from `docs/research/16kb-page-size.md`:
  - [x] ELF alignment of every 64-bit `.so`, libraries stored uncompressed, and `zipalign -c -P 16` on the APK: `dev/check-16kb.sh`.
  - [x] The same on the APKs that bundletool (pinned in `dev/pinned-tool.sh`) generates from the release App Bundle.
  - [x] Fails when `arm64-v8a` or `x86_64` has no `libgojni.so`, or when the debug APK or the release bundle is missing.
- [x] Gradle dependency verification (`gradle/verification-metadata.xml`) on: enforced by `dev/check-repo.sh`.
- [x] Dependabot covers `gradle` and `gomod` (enforced by `dev/check-repo.sh`); OSV-Scanner audits `go.mod` daily.
- [ ] Decided with the user: known vulnerabilities in the Go standard library that the bridge is built with. See Next.
- [x] Release signing keys only as secrets of a protected environment, never in pull request workflows: `dev/lint-workflows.sh` reads the workflows as YAML and rejects a secret outside a job with an environment, secrets passed to a called workflow, and any secret, environment or remote called workflow in a workflow that runs for pull requests.
- [x] Instrumented tests on an emulator in CI: `.github/workflows/emulator.yml`, API 34 with 4 KB pages and API 36 with 16 KB pages.
- [ ] Decided with the user: a check that dependencies are free software for F-Droid. See Next.
- [x] The `android` job passes in CI on the real Gradle project.

## Done
- `android` job: debug APK with 4 64-bit libraries, release App Bundle with 4 64-bit libraries in 79 generated split APKs, all 16 KB-aligned and stored uncompressed. The bundle config has `PAGE_ALIGNMENT_16K`. The job takes about 6 minutes, up from under 3, because of the release bundle (R8) and the test APK.
- `dev/check-16kb.sh` fails crafted APKs without `lib/x86_64/libgojni.so` (also when another `x86_64` library is left), with compressed libraries, and with a LOAD segment aligned to 4 KB; it also fails on a missing input file.
- `dev/lint-workflows.sh` fails crafted workflows: quoted `"on"`, `"pull_request"` and `"environment"` keys, a multiline `${{ }}` expression, a secret in an `if` condition, workflow-level `env`, `secrets: inherit` and a `secrets` mapping on a calling job, a local called workflow with an environment, a remote called workflow, and a zizmor ignore comment. `GITHUB_TOKEN` and `inputs.secrets` pass.
- Emulator workflow (run 43777672069): API 34 job 4 min 57 s, API 36 16 KB job 5 min 36 s; 2 tests each, under 20 s. The 16 KB image reports page size 16384; the app has `pageSizeCompat=0`, the 4 KB-aligned control APK gets 2. The tests passed in all 3 runs on each image, and on an android-broker API 34 emulator.
- OSV-Scanner reads no `stdlib` package from `engine/go.mod`. `govulncheck` v1.9.0 on `engine/` (`-tags noassets`, Go 1.26.2) reports 23 reachable standard library vulnerabilities, all fixed by Go 1.26.9, and none in modules. No check watches for Go toolchain patch releases on their own; the `go` line changes only when a module update raises it.

## Next
Questions for the user:

1. Go standard library: raise the `go` line in `engine/go.mod` to 1.26.9 (engine task), then add `govulncheck` (pinned version, `-tags noassets`, on the Go version from `engine/go.mod`) as a second job of `.github/workflows/dependency-audit.yml`? Without the version bump the new job fails at once.
2. F-Droid free-software check, if wanted. Options: (a) a deny list in `dev/check-repo.sh` of known non-free groups (`com.google.android.gms`, `com.google.firebase`, `com.google.android.play`, and similar) in the app's runtime classpath, plus only `google()` and `mavenCentral()` as repositories; offline, about 30 lines, catches the common cases only. (b) A license allow list: `app.cash.licensee` for the Gradle runtime classpath and `go-licenses check` for `engine/`; fails on unknown or non-free licenses, adds a Gradle plugin and its verification entries, and its output can feed the licences screen. (c) `fdroid scanner` from a pinned fdroidserver in a weekly job: F-Droid's own rules, about 1 to 2 minutes, needs triage of its findings. Suggested: (b), with (a) as a cheap first step.
3. End-to-end check with a Syncthing peer on a runner: `dev/e2e-emulator.sh` would need a mode that uses the connected emulator instead of `android-broker`, plus a nightly or on-demand job. Add it?
