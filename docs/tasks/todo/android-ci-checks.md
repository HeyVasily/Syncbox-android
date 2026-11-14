---
title: Add Android build checks to CI
assignee: agent
depends_on: []
touches:
  - .github/
  - dev/
  - docs/ci.md
---

## Goal
CI builds and checks the app once the Gradle project exists.

## Acceptance
- [x] Gradle wrapper validation (`gradle/actions/wrapper-validation`, pinned by SHA). In `ci.yml`; has not run yet, because there is no wrapper.
- [x] Debug build, Android Lint and unit tests run on every pull request and on `master`: `dev/check-android.sh`. Tested with a stub `gradlew` only.
- [ ] 16 KB checks from `docs/research/16kb-page-size.md`:
  - [x] ELF alignment of every 64-bit `.so` and `zipalign -c -P 16` on the APK: `dev/check-16kb.sh`, tested on real and crafted APKs.
  - [ ] The same on APKs generated from the App Bundle (bundletool, pinned in `dev/pinned-tool.sh`).
- [x] Gradle dependency verification (`gradle/verification-metadata.xml`) is on: enforced by `dev/check-repo.sh` once `gradlew` exists.
- [ ] Dependabot also covers `gradle` and `gomod`; `govulncheck` runs on the Go bridge. Dependabot coverage is enforced by `dev/check-repo.sh`; OSV-Scanner audits `go.mod` daily. `govulncheck` is not added.
- [ ] Release signing keys exist only as secrets of a protected environment, never in pull request workflows.
- [ ] Decided with the user: instrumented tests on an emulator in CI, and a check that dependencies are free software for F-Droid.
- [ ] The `android` job has passed in CI on the real Gradle project. It ran on 2026-11-14: build, Lint and 16 KB checks work, one unit test fails.

## Next
The Gradle project now exists and the checks ran on it for the first time (2026-11-14). Three of them fail and need a fix in the project, not in CI:
1. `repository`: generate `gradle/verification-metadata.xml` (`./gradlew --write-verification-metadata sha256 lint testDebugUnitTest assembleDebug`, with `<verify-metadata>true</verify-metadata>`). Dependabot does not regenerate this file, so its Gradle pull requests fail until it is regenerated.
2. `osv`: `engine/go.mod` has known advisories in `golang.org/x/crypto` 0.57.0 and `golang.org/x/net` 0.60.0. Update them, or accept each one with a reason in a root `osv-scanner.toml`.
3. `android`: the unit test `FilesViewModelTest.stoppingTheTapDownloadEndsThePendingOpenEvenWithASetAsideSync` fails. Lint, the debug build and the 16 KB check of its 2 libraries pass.

Then do the unchecked items above. Known traps: `testDebugUnitTest` does not exist once product flavors are added; the verification file also lists build-time artifacts, so the OSV audit may report advisories for them. Once the Go engine is always in the APK, make `dev/check-16kb.sh` fail when an ABI has no library. Ask the user about emulator tests and the F-Droid check.

## Log
- 2026-11-12T23:20Z claude-opus: created.
- 2026-11-14T02:20Z claude-opus: at the user's request added what can be built and tested before the Gradle project exists: `dev/check-android.sh`, `dev/check-16kb.sh`, `dev/audit-deps.sh`, the dependency rules in `dev/check-repo.sh`, the `android` and `dependency-review` jobs and the dependency audit workflow. Evidence: `check-16kb.sh` passes the sibling project's x86_64 APK and a crafted 16 KB APK, and fails a crafted 4 KB library and a ZIP-misaligned APK; the dependency rules report all four violations on a fixture; `audit-deps.sh` reports known advisories for a fixture `go.mod`. The Gradle steps themselves have never run.
- 2026-11-14T03:00Z claude-opus: review round 1 (two independent reviewers) rejected the candidate. Fixed: the OSV audit now also runs on pull requests that change a manifest, dependency review covers all scopes, the build uses strict dependency verification, the Dependabot rule ignores comments and accepts quotes, a wrapper outside the root is an error, pushes to `master` no longer share a concurrency group, a malformed ELF is reported instead of crashing, and an empty manifest no longer fails the audit.
- 2026-11-14T15:40Z claude-opus: review round 2: one reviewer confirmed the round 1 fixes and found that a root `osv-scanner.toml` was not applied to manifests in subfolders; fixed with `--config`. Also: the verification file is exempt from the 1 MiB limit, and the preview Build Tools filter looks only at the version folder.
- 2026-11-14T16:20Z claude-opus: the Gradle project landed on `master` while this was in review. Merged `master`, added the `gradle` and `gomod` Dependabot ecosystems, and the checks ran on the real project in the pull request. Evidence: the `android` job built the APK and checked 2 libraries; `repository`, `osv` and `android` fail for the reasons in Next. Moved to `todo/`.
