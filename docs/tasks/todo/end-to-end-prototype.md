---
title: Build and verify an end-to-end prototype
assignee: agent
depends_on:
  - verification-setup
touches: []
---

## Goal
The riskiest assumptions are proven by a working prototype: embedding Syncthing, selective sync into internal storage, and opening a file in VLC.

## Context
- Design: `docs/design/flows.md` and `docs/design/ui.md`. Decisions: `docs/decisions.md`.
- Research: `docs/research/build-strategy.md`. Nothing in it was built or run. Its `[I]` and `[U]` claims are assumptions, and its `[V]` claims were read on Syncthing `main`, not on the pinned version.
- The report is not a decision. Where it disagrees with `docs/decisions.md` or the flows, the docs win until the user changes them.

## Working rule
Any behaviour the prototype relies on is checked locally and covered by a local test as part of this task. Do not split verification into separate tasks. Checks that need no Android (Syncthing behaviour) run as host-side tests against a second Syncthing instance, before the emulator.

## Acceptance
- [ ] Syncthing runs in-process and pairs with a peer.
- [ ] One selected file syncs into internal storage, and an unselected file does not.
- [ ] Deselecting a file behaves as designed.
- [ ] A sync continues after the app is minimized and shows progress in a notification.
- [ ] Pause and stop end the job and stop Syncthing. Resuming continues from the partial download, and how long Syncthing keeps partial files is recorded.
- [ ] The file opens and seeks in VLC through `FileProvider`.
- [ ] External subtitle behaviour in VLC is recorded.
- [ ] The acceptance tests from section 5 of `docs/research/16kb-page-size.md` that apply to the prototype pass inside the installed app on a 16 KB image with compatibility mode off.
- [ ] Each of these is verified by a local test, with the result recorded in `Log`:
  - A paused folder does not receive index updates, so the file list does not refresh while paused.
  - What downloads without pressing Sync when the app is only open: new versions of files on the device, new files under a selected directory. Whether a resting whitelist of per-file lines, or a large `pullerDelayS`, prevents it without re-hashing files.
  - Deselect in the order "change ignores, wait for the scan, delete the file": no locally changed items remain and the peer shows the phone as up to date. Select, stop, deselect leaves no stuck state.
  - The rule for "sync finished" is reliable right after an ignore change.
  - Syncthing starts and stops many times in one process without leaking goroutines, file descriptors or memory, and stops cleanly mid-transfer.
  - Partial files survive stop, pause and a process kill, and are reused on resume.
  - Scan time with many ignore lines against a large remote folder.
  - File names with `\ * ? [ ] { }`, leading `#` or `!`, trailing spaces and non-ASCII characters select and deselect correctly.
- [ ] Every other assumption the prototype ends up relying on is tested the same way. The table "A prototype must settle these" in the research report lists the known ones.
- [ ] Findings that contradict `docs/decisions.md` or `docs/design/flows.md` are reported to the user with the evidence. The known one: the flows say nothing downloads until Sync is pressed, and stock Syncthing has no option for that.

## Next
Wait for `verification-setup`, or for the user to say how this environment builds and runs the app. Then claim the task, set `touches`, read the research report, and start with the host-side Syncthing tests.

## Log
- 2026-11-02T00:00Z claude-opus: created. The user asked for no prototypes until design and setup are finished.
- 2026-11-14T01:57Z claude-fable: the user said the design is ready and that another agent in another environment will implement this; removed the `app-design` dependency. Added the research report, the working rule and the list of behaviours to verify locally.
