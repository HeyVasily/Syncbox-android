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

## Steps
- [x] `bash dev/build-app.sh` passes: debug and R8 release builds, lint with no errors, 27 unit tests.
- [x] The app runs on the API 36 emulator; `bash dev/screenshots.sh` retakes the 24 screenshots in `docs/design/screenshots/`.
- [x] Two independent reviews; findings fixed or answered.
- [x] `bash dev/check.sh` passes.
- [ ] The user accepts the screens and the engine seam.

## Done
- Merged as #5.
- Engine seam: `FileRef(folderId, path)`, one status `StateFlow` plus per-level `entries` and `onDevice` flows, folder offers as a list, `AppSettings` outside the engine. Debug scenes live only in `src/debug`.
- Later changes on `master`: folder rows show the selected size (#7), the package is `app.avoska`, and `RestSyncEngine` implements the seam with the real engine (#11). The project stays on compileSdk 36 (`compile-sdk-37`).

## Next
Wait for the user to accept the screens and the engine seam.
