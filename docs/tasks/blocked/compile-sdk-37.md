---
title: Move compileSdk to 37
assignee: agent
depends_on: []
touches:
  - app/build.gradle.kts
  - gradle/libs.versions.toml
  - gradle/verification-metadata.xml
  - .github/dependabot.yml
  - .github/workflows/**
  - dev/**
  - docs/**
---

## Goal
The app compiles against android-37, so AndroidX and Compose releases that need compileSdk 37 can be taken. minSdk and targetSdk do not change.

## Acceptance
- [ ] `compileSdk = 37` in `app/build.gradle.kts`; CI and the local setup install platform android-37.
- [ ] The android-36 notes are updated: the comment in `gradle/libs.versions.toml`, `dev/build-app.sh`, `dev/build-bridge.sh`, `docs/verification.md`, `docs/design/screenshots/README.md`.
- [ ] The Compose BOM `ignore` rule in `.github/dependabot.yml` is removed, and the Compose BOM is on the newest stable version.
- [ ] AGP 9.5.1 (or the version in use) supports compileSdk 37 without warnings.
- [ ] `bash dev/check.sh` passes, and all CI checks pass.

## Next
Blocked: the yard has only platform android-36, and installing another SDK platform on request needs a Subyard update. When the yard can install android-37, move this task to `todo/`.

## Log
- 2026-11-15T22:32Z claude-opus: created. Dependabot PR #13 (Compose BOM 2026.06.01 → 2026.09.00, Compose 1.12.1) failed `:app:checkDebugAarMetadata`: 9 Compose libraries need compileSdk 37. The user deferred the move until Subyard supports installing SDKs on request; until then Dependabot ignores Compose BOM 2026.08.00 and later (Compose 1.12), and #13 is closed.
