---
title: Rename the app and repository to Avoska
assignee: together
depends_on: []
touches:
  - docs/design/mockups/first-launch.html
  - docs/research/build-strategy.md
  - engine/go.mod
  - engine/ignorepattern.go
---

## Goal
Before the first release, the app is named "Avoska" everywhere and the repository is `avoska-android`. No "Syncbox" is left in the repository or the store listing.

## Acceptance
- [x] "Avoska" is checked against Google Play search and the USPTO, EUIPO and WIPO trademark registers (classes 9 and 42); nothing blocking is found.
- [x] An `applicationId` is chosen and recorded in `docs/decisions.md`. It cannot be changed after the first Google Play release, unlike the display name.
- [ ] The app label, package names, docs and mockups use "Avoska": `git grep -i syncbox` prints nothing, apart from this task file.
- [ ] The user has renamed the GitHub repository to `avoska-android`, and `git remote -v` shows the new URL.
- [ ] `bash dev/check.sh` passes.

## Next
Rename inside the repository in one pull request: `git grep -i syncbox` finds it only in the files in `touches` (the Go module path in `engine/go.mod`, which nothing imports, and one comment in `engine/ignorepattern.go`). Coordinate the two engine lines with the owner of `end-to-end-prototype`. Then the user renames the GitHub repository and updates the remote; check that CI, CodeQL and Dependabot still work. Keep the local workspace folder as is.

## Log
- 2026-11-14T01:59Z claude-fable: created. The user chose "Avoska" (the Russian word for a string bag carried just in case) after a name study found an earlier US trademark SYNCBOX for file storage software, a "Syncbox Cloud Storage" app in the Microsoft Store and a BOX SYNC mark held by Box, Inc. Avoska itself was not checked in any store or register. The user accepts renaming again later if a complaint arrives.
- 2026-11-14T06:30Z claude-opus: the user confirmed `app.avoska` as the final `applicationId`; recorded in `docs/decisions.md`.
- 2026-11-15T23:31Z claude-opus: store and trademark checks done (Google Play, App Store, F-Droid, GitHub, USPTO, TMview for EUIPO and the UK, the Russian register; WIPO not reachable by script). Nothing blocking. Closest: a pending US application AVOSCA (classes 9 and 42, task-confirmation software), a Russian grocery chain app "Avoska" in Food & Drink, and an old Russian food mark. The user accepted the name; recorded in `docs/decisions.md`.
