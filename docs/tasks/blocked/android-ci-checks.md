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
- [ ] The `android` job has passed in CI on the real Gradle project.

## Next
Blocked until the Gradle project exists. Then, in the change that adds it: add the `gradle` and `gomod` ecosystems to `.github/dependabot.yml`, pin `distributionSha256Sum`, generate `gradle/verification-metadata.xml` (`dev/check-repo.sh` fails until all three are done), and make the `android` job in `ci.yml` pass: it may need Go and the NDK set up for the bridge build. Known traps: `testDebugUnitTest` does not exist once product flavors are added; Dependabot does not regenerate `gradle/verification-metadata.xml`, so its Gradle pull requests fail until the file is regenerated; the verification file also lists build-time artifacts, so the first OSV audit may report advisories that need a fix or an `osv-scanner.toml` entry. Once the Go bridge is in the APK, make `dev/check-16kb.sh` fail when an ABI has no library. After that, do the unchecked items above. Ask the user about emulator tests and the F-Droid check.

## Log
- 2026-11-12T23:20Z claude-opus: created.
- 2026-11-14T02:20Z claude-opus: at the user's request added what can be built and tested before the Gradle project exists: `dev/check-android.sh`, `dev/check-16kb.sh`, `dev/audit-deps.sh`, the dependency rules in `dev/check-repo.sh`, the `android` and `dependency-review` jobs and the dependency audit workflow. Evidence: `check-16kb.sh` passes the sibling project's x86_64 APK and a crafted 16 KB APK, and fails a crafted 4 KB library and a ZIP-misaligned APK; the dependency rules report all four violations on a fixture; `audit-deps.sh` reports known advisories for a fixture `go.mod`. The Gradle steps themselves have never run.
- 2026-11-14T03:00Z claude-opus: review round 1 (two independent reviewers) rejected the candidate. Fixed: the OSV audit now also runs on pull requests that change a manifest, dependency review covers all scopes, the build uses strict dependency verification, the Dependabot rule ignores comments and accepts quotes, a wrapper outside the root is an error, pushes to `master` no longer share a concurrency group, a malformed ELF is reported instead of crashing, and an empty manifest no longer fails the audit.
