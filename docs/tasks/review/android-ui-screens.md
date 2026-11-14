---
title: Build the approved screens as an Android app on demo data
assignee: agent
depends_on: []
touches:
  - settings.gradle.kts
  - build.gradle.kts
  - gradle.properties
  - gradle/**
  - gradlew
  - gradlew.bat
  - app/**
  - dev/build-app.sh
  - dev/screenshots.sh
  - docs/design/screenshots/**
---

## Goal
The first Gradle project exists. It shows every screen from `docs/design/mockups/` (except the system notification) on in-memory demo data, in the light and dark themes, behind a `SyncEngine` interface that the real Syncthing engine can implement later.

## Acceptance
- [x] `bash dev/build-app.sh` passes: debug and R8 release builds, lint with no errors, 27 unit tests.
- [x] The app runs on the API 36 emulator; `bash dev/screenshots.sh` retakes the 24 screenshots in `docs/design/screenshots/`.
- [x] Two independent reviews; findings fixed or answered.
- [x] `bash dev/check.sh` passes.
- [ ] The user accepts the screens and the engine seam.

## Next
Wait for the user's review of the pull request. Open questions for the user: folder rows show the total size (as the mockup does) while `flows.md` says the selected size; the `applicationId` `app.avoska` is provisional; AndroidX versions are held back because only SDK platform 36 is installed.

## Log
- 2026-11-14T02:40Z claude-opus: created at the user's request; developer and reviewer sessions ran in a separate worktree.
- 2026-11-14T05:00Z claude-opus: three review rounds done. Seam: `FileRef(folderId, path)`, one status `StateFlow` plus per-level `entries`/`onDevice` flows, folder offers as a list, `AppSettings` outside the engine. Debug scenes only in `src/debug`. Build, tests, lint and repository checks pass.
