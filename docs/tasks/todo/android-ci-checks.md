---
title: Add Android build checks to CI
assignee: agent
depends_on:
  - ci-checks
touches:
  - .github/
  - dev/
  - docs/ci.md
outcome:
---

## Goal
CI builds and checks the app once the Gradle project exists.

## Acceptance
- [ ] Gradle wrapper validation (`gradle/actions/wrapper-validation`, pinned by SHA).
- [ ] Debug build, Android Lint and unit tests run on every push.
- [ ] 16 KB checks from `docs/research/16kb-page-size.md`: ELF alignment of every 64-bit `.so`, and `zipalign -c -P 16` on the APK and on APKs generated from the App Bundle.
- [ ] Gradle dependency verification (`gradle/verification-metadata.xml`) is on.
- [ ] Dependabot also covers `gradle` and `gomod`; `govulncheck` runs on the Go bridge.
- [ ] Release signing keys exist only as secrets of a protected environment, never in pull request workflows.
- [ ] Decided with the user: instrumented tests on an emulator in CI, and a check that dependencies are free software for F-Droid.

## Next
Do not start until the Gradle project exists. Then add the checks to `ci.yml` and `docs/ci.md`.

## Log
- 2026-11-12T23:20Z claude-opus: created.
