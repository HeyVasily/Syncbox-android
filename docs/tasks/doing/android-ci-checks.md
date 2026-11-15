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
  - [x] Fails when `arm64-v8a` or `x86_64` has no library.
- [x] Gradle dependency verification (`gradle/verification-metadata.xml`) on: enforced by `dev/check-repo.sh`.
- [x] Dependabot covers `gradle` and `gomod` (enforced by `dev/check-repo.sh`); OSV-Scanner audits `go.mod` daily.
- [ ] Known vulnerabilities in the Go standard library that the bridge is built with: OSV-Scanner does not see them; `govulncheck` does. See Next.
- [x] Release signing keys only as secrets of a protected environment, never in pull request workflows: `dev/lint-workflows.sh` rejects a secret in a job without an environment, and any secret or environment in a workflow that pull requests trigger.
- [ ] Instrumented tests on an emulator in CI: `.github/workflows/emulator.yml`, API 34 with 4 KB pages and API 36 with 16 KB pages.
- [ ] Decided with the user: a check that dependencies are free software for F-Droid.
- [x] The `android` job passes in CI on the real Gradle project.

## Done
- `android` job on `master` (run 43773660817): debug APK built, both bridge libraries 16 KB-aligned, 4 64-bit libraries checked.
- `dev/check-16kb.sh` passes the debug APK and the release App Bundle (79 generated split APKs, 4 64-bit libraries); it fails crafted APKs without an `x86_64` library, with compressed libraries, and with a LOAD segment aligned to 4 KB.
- The bundle config stores libraries uncompressed with `PAGE_ALIGNMENT_16K`.
- `dev/lint-workflows.sh` fails crafted workflows: a secret without an environment, an environment or a secret in a `pull_request` workflow, `secrets: inherit` in a `pull_request_target` workflow; the repository's workflows pass.
- `dev/instrumented-tests.sh` passes on an API 34 emulator from android-broker: 2 tests in 22 s.
- OSV-Scanner reads no `stdlib` package from `engine/go.mod`. `govulncheck` v1.9.0 on `engine/` (`-tags noassets`) reports 23 reachable standard library vulnerabilities in Go 1.26.2, all fixed by Go 1.26.9, and none in modules.

## Next
Run `.github/workflows/emulator.yml` in the pull request and record the result of both matrix jobs.
