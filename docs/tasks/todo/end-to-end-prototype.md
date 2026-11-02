---
title: Build and verify an end-to-end prototype
assignee: agent
depends_on:
  - app-design
  - verification-setup
touches: []
outcome:
---

## Goal
The riskiest assumptions are proven on the emulator: embedding Syncthing, selective sync into internal storage, and opening a file in VLC.

## Acceptance
- [ ] Syncthing runs in-process and pairs with a peer.
- [ ] One selected file syncs into internal storage, and an unselected file does not.
- [ ] Deselecting a file behaves as designed.
- [ ] A sync continues after the app is minimized and shows progress in a notification.
- [ ] Pause and stop end the job and stop Syncthing. Resuming continues from the partial download, and how long Syncthing keeps partial files is recorded.
- [ ] The file opens and seeks in VLC through `FileProvider`.
- [ ] External subtitle behaviour in VLC is recorded.

## Next
Do not start until `app-design` and `verification-setup` are in `done/`. Set `touches` when the task is claimed.

## Log
- 2026-11-02T00:00Z claude-opus: created. The user asked for no prototypes until design and setup are finished.
