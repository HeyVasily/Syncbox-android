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

## Steps
- [x] "Avoska" is checked against Google Play search and the USPTO, EUIPO and WIPO trademark registers (classes 9 and 42); nothing blocking is found.
- [x] An `applicationId` is chosen and recorded in `docs/decisions.md`. It cannot be changed after the first Google Play release, unlike the display name.
- [ ] The app label, package names, docs and mockups use "Avoska": `git grep -i syncbox` prints nothing, apart from this task file.
- [ ] The user has renamed the GitHub repository to `avoska-android`, and `git remote -v` shows the new URL.
- [ ] `bash dev/check.sh` passes.

## Done
- The name "Avoska" is accepted and recorded in `docs/decisions.md`; the `applicationId` is `app.avoska`.
- "Syncbox" is dropped because of an earlier US trademark SYNCBOX for file storage software, a "Syncbox Cloud Storage" app in the Microsoft Store and a BOX SYNC mark held by Box, Inc.
- Name check: Google Play, App Store, F-Droid, GitHub, USPTO, TMview (EUIPO and the UK) and the Russian register; WIPO could not be queried by script. Nothing blocking. Closest: a pending US application AVOSCA (classes 9 and 42, task-confirmation software), a grocery chain app "Avoska" in Food & Drink, and an old Russian food mark. If a complaint arrives, the app is renamed again.
- The app code already uses `app.avoska`. Apart from this task file, `git grep -i syncbox` finds the name only in the files in `touches`; in `engine/` that is the Go module path in `go.mod` (nothing imports it) and one comment in `ignorepattern.go`.

## Next
Rename inside the repository in one pull request. `engine/**` belongs to `end-to-end-prototype` in `doing/`, so change the two engine lines together with that task or after it. Then the user renames the GitHub repository and updates the remote; check that CI, CodeQL and Dependabot still work. Keep the local workspace folder as is.
