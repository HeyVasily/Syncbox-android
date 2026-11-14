---
title: Rename the app and repository to Avoska
assignee: together
depends_on: []
touches:
  - AGENTS.md
  - docs/**
  - dev/**
---

## Goal
Before the first release, the app is named "Avoska" everywhere and the repository is `avoska-android`. No "Syncbox" is left in the repository or the store listing.

## Acceptance
- [ ] "Avoska" is checked against Google Play search and the USPTO, EUIPO and WIPO trademark registers (classes 9 and 42); nothing blocking is found.
- [ ] An `applicationId` is chosen and recorded in `docs/decisions.md`. It cannot be changed after the first Google Play release, unlike the display name.
- [ ] The app label, package names, docs and mockups use "Avoska": `git grep -i syncbox` prints nothing, apart from this task file.
- [ ] The user has renamed the GitHub repository to `avoska-android`, and `git remote -v` shows the new URL.
- [ ] `bash dev/check.sh` passes.

## Next
Run the Google Play and trademark checks for "Avoska". Then agree the `applicationId` with the user. Rename inside the repository only when no task in `doing/` overlaps `touches`; `docs/design/mockups/first-launch.html` and `docs/research/build-strategy.md` mention "Syncbox" today. Renaming the GitHub repository and the local workspace folder is the user's step.

## Log
- 2026-11-14T01:59Z claude-fable: created. The user chose "Avoska" (the Russian word for a string bag carried just in case) after a name study found an earlier US trademark SYNCBOX for file storage software, a "Syncbox Cloud Storage" app in the Microsoft Store and a BOX SYNC mark held by Box, Inc. Avoska itself was not checked in any store or register. The user accepts renaming again later if a complaint arrives.
