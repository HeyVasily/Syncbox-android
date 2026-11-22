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

## Steps
- [ ] `compileSdk = 37` in `app/build.gradle.kts`; CI and the local setup install platform android-37.
- [ ] The android-36 notes are updated: the comment in `gradle/libs.versions.toml`, `dev/build-app.sh`, `dev/build-bridge.sh`, `docs/verification.md`, `docs/design/screenshots/README.md`.
- [ ] The compileSdk 37 `ignore` rules in `.github/dependabot.yml` (Compose BOM, okhttp) are removed, and both are on the newest stable versions.
- [ ] AGP 9.5.2 (or the version in use) supports compileSdk 37 without warnings.
- [ ] `bash dev/check.sh` passes, and all CI checks pass.

## Done
- Dependabot PR #13 (Compose BOM 2026.06.01 to 2026.09.00, Compose 1.12.1) failed `:app:checkDebugAarMetadata`: 9 Compose libraries need compileSdk 37. #13 is closed.
- Dependabot PR #15 (okhttp 5.4.0 to 5.5.1) failed the same way: `okhttp-android` 5.5.0 and later need compileSdk 37. #15 is closed.
- Until this task is done, Dependabot ignores Compose BOM 2026.08.00 and later (#14) and okhttp 5.5.0 and later (#18).

## Next
Blocked: the yard has only platform android-36, and installing another SDK platform on request needs a Subyard update. When the yard can install android-37, move this task to `todo/`.
