---
title: Build and verify an end-to-end prototype
assignee: agent
depends_on:
  - verification-setup
touches:
  - engine/**
  - dev/test-engine.sh
  - docs/research/engine-host-findings.md
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
- [x] Each of these is verified by a local test, with the result recorded in `Log` (host-side, `engine/`; Android parts still open):
  - A paused folder does not receive index updates, so the file list does not refresh while paused.
  - What downloads without pressing Sync when the app is only open: new versions of files on the device, new files under a selected directory. Whether a resting whitelist of per-file lines, or a large `pullerDelayS`, prevents it without re-hashing files.
  - Deselect in the order "change ignores, wait for the scan, delete the file": no locally changed items remain and the peer shows the phone as up to date. Select, stop, deselect leaves no stuck state.
  - The rule for "sync finished" is reliable right after an ignore change.
  - Syncthing starts and stops many times in one process without leaking goroutines, file descriptors or memory, and stops cleanly mid-transfer.
  - Partial files survive stop, pause and a process kill, and are reused on resume.
  - Scan time with many ignore lines against a large remote folder.
  - File names with `\ * ? [ ] { }`, leading `#` or `!`, trailing spaces and non-ASCII characters select and deselect correctly.
- [ ] Every other assumption the prototype ends up relying on is tested the same way. The table "A prototype must settle these" in the research report lists the known ones.
- [x] Findings that contradict `docs/decisions.md` or `docs/design/flows.md` are reported to the user with the evidence. The known one: the flows say nothing downloads until Sync is pressed, and stock Syncthing has no option for that.

## Next
Host-side Syncthing questions are answered in `docs/research/engine-host-findings.md` (run `bash dev/test-engine.sh`). The user asked for a model with two network levels: offline mode, and a separate download gate, with the file list refreshing on any network and downloads running only in a background job. A research pass found a stock gate (folder `minDiskFree` set out of reach); port its host tests into `engine/`, then agree the decision with the user and update `docs/decisions.md` and `docs/design/flows.md`. Android parts (bridge, UIDT job, notification, VLC, 16 KB) remain.

## Log
- 2026-11-02T00:00Z claude-opus: created. The user asked for no prototypes until design and setup are finished.
- 2026-11-14T01:57Z claude-fable: the user said the design is ready and that another agent in another environment will implement this; removed the `app-design` dependency. Added the research report, the working rule and the list of behaviours to verify locally.
- 2026-11-14T05:30Z claude-opus: claimed at the user's request for the host-side part. Added `engine/` (pinned Syncthing v2.1.7, Go 1.26.2) with 29 host tests and the findings report; three review rounds. `dev/test-engine.sh` exit 0, 29/29, 278 s. Results: paused folder gets no index; armed content downloads by itself about 1.3 s after an index change; ignore, scan, delete is clean, delete, scan, ignore leaves the peer at 0%; nested selections need ancestor lines; finished rule reliable after the scan; 50 start/stop cycles without goroutine, descriptor or Go heap growth; partial files survive stop and kill; 100k files with 5k lines scan in about 9 s; 47 special-name targets pass.
