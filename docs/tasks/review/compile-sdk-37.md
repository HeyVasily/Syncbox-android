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
- [x] `app/build.gradle.kts` selects compileSdk 37.2 and Build Tools 37.0.0.
- [x] The SDK requirements in the build scripts and docs match the Android 37 build.
- [x] The Compose BOM and OkHttp holds are removed from Dependabot, and both use the latest stable releases.
- [x] AGP 9.5.2 supports compileSdk 37.2 without SDK warnings.
- [x] `bash dev/check.sh` passes, and all CI checks pass.

## Done
- The build uses Android 37.2 and Build Tools 37.0.0, with minSdk 34 and targetSdk 36.
- The version catalog uses Compose BOM 2026.11.01 and OkHttp 5.5.1. Gradle verification metadata covers both upgrades.
- `bash dev/check.sh` passes with strict dependency verification and 16 KB packaging checks. The dependency audit reports no new advisories.
- All CI checks pass, including the emulator jobs on API 34 and API 36. [PR #23 checks](https://github.com/HeyVasily/Syncbox-android/pull/23/checks)

## Next
Review the pull request.
