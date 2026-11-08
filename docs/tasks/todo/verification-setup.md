---
title: Agree on and build the verification setup
assignee: together
depends_on: []
touches:
  - docs/verification.md
outcome:
---

## Goal
An agent can build the app and check it end to end on its own, using the yard's emulator and a Syncthing peer. This setup is not a project deliverable.

## Acceptance
- [ ] The approach is agreed with the user and documented in `docs/verification.md`.
- [ ] An agent can run the app on the emulator and reach a Syncthing peer.
- [ ] An agent can check opening a file in VLC on the emulator.
- [ ] A 16 KB emulator image and a 4 KB API 34 image are available, and `getconf PAGE_SIZE` is confirmed on each.
- [ ] The plan for ARM64 testing before release is agreed, since x86-64 images only simulate 16 KB.
- [ ] The toolchain builds an app with a gomobile library, and the 16 KB alignment checks run on it.

## Next
Discuss the approach with the user, then check what the yard provides.

## Log
- 2026-11-02T00:00Z claude-opus: created.
