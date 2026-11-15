---
title: Build and verify an end-to-end prototype
assignee: agent
depends_on:
  - verification-setup
touches:
  - app/**
  - engine/**
  - dev/**
  - gradle/**
  - .github/workflows/**
  - docs/decisions.md
  - docs/ci.md
  - docs/verification.md
  - docs/research/engine-host-findings.md
  - docs/design/screenshots/**
---

## Goal
The riskiest assumptions are proven by a working prototype: embedding Syncthing, selective sync into internal storage, and opening a file in VLC.

## Context
- Design: `docs/design/flows.md` and `docs/design/ui.md`. Decisions: `docs/decisions.md`.
- Research: `docs/research/build-strategy.md`. Nothing in it was built or run. Its `[I]` and `[U]` claims are assumptions, and its `[V]` claims were read on Syncthing `main`, not on the pinned version.
- The report is not a decision. Where it disagrees with `docs/decisions.md` or the flows, the docs win until the user changes them.

## Working rule
Any behaviour the prototype relies on is checked locally and covered by a local test as part of this task. Do not split verification into separate tasks. Checks that need no Android (Syncthing behaviour) run as host-side tests against a second Syncthing instance, before the emulator.

## Steps
- [x] Syncthing runs in-process and pairs with a peer.
- [ ] One selected file syncs into internal storage, and an unselected file does not.
- [ ] Deselecting a file behaves as designed.
- [ ] A sync continues after the app is minimized and shows progress in a notification.
- [ ] Pause and stop end the job and stop Syncthing. Resuming continues from the partial download, and how long Syncthing keeps partial files is recorded.
- [ ] The file opens and seeks in VLC through `FileProvider`.
- [ ] External subtitle behaviour in VLC is recorded.
- [ ] The acceptance tests from section 5 of `docs/research/16kb-page-size.md` that apply to the prototype pass inside the installed app on a 16 KB image with compatibility mode off.
- [x] Each of these is verified by a local test, with the result recorded in `Done` (host-side, `engine/`; Android parts still open):
  - A paused folder does not receive index updates, so the file list does not refresh while paused.
  - What downloads without pressing Sync when the app is only open: new versions of files on the device, new files under a selected directory. Whether a resting whitelist of per-file lines, or a large `pullerDelayS`, prevents it without re-hashing files.
  - Deselect in the order "change ignores, wait for the scan, delete the file": no locally changed items remain and the peer shows the phone as up to date. Select, stop, deselect leaves no stuck state.
  - The rule for "sync finished" is reliable right after an ignore change.
  - Syncthing starts and stops many times in one process without leaking goroutines, file descriptors or memory, and stops cleanly mid-transfer.
  - Partial files survive stop, pause and a process kill, and are reused on resume.
  - Scan time with many ignore lines against a large remote folder.
  - File names with `\ * ? [ ] { }`, leading `#` or `!`, trailing spaces and non-ASCII characters select and deselect correctly.
- [ ] Every other assumption the prototype ends up relying on is tested the same way. The table "A prototype must settle these" in the research report lists the known ones.
- [x] Findings that contradict `docs/decisions.md` or `docs/design/flows.md` are reported to the user with the evidence. The known one: the flows said nothing downloads until Sync is pressed, and stock Syncthing has no option for that.

## Done
- Host side: `engine/` embeds pinned Syncthing v2.1.7 (Go 1.26.2); `bash dev/test-engine.sh` runs the host tests. Findings are in `docs/research/engine-host-findings.md`: a paused folder gets no index; armed content downloads by itself about 1.3 s after an index change; ignore, scan, delete is clean, while delete, scan, ignore leaves the peer at 0%; nested selections need ancestor lines; the finished rule is reliable after the scan; 50 start/stop cycles show no goroutine, descriptor or Go heap growth; partial files survive stop and kill; 100k files with 5k ignore lines scan in about 9 s; 47 special-name targets pass.
- The contradiction is resolved by the network-levels model and the stock download gate (folder `minDiskFree` out of reach), recorded in `docs/decisions.md` and `docs/design/flows.md` (flow 5a). The gate is tested in `engine/gate_test.go`.
- Milestone 1, #11: the gomobile bridge `engine/bridge` (`Start(home, downloadJob)` writes the gate closed on every start outside a download job; REST is reachable only with the API key), `RestSyncEngine` in `app/`, and `dev/e2e-emulator.sh` (API 36 x86_64, peer on the host). On the emulator the app pairs, accepts a folder, lists the peer's files, downloads nothing, and stops Syncthing in the background and in offline mode. Details are in `docs/verification.md` and `engine/README.md`.
- The `verification-setup` dependency is not finished; the verification this task needs is built here and documented in `docs/verification.md`.
- 16 KB runtime is untested: the emulator broker has no 16 KB image.

## Next
Milestone 2, downloads. Start with the UIDT job for Sync: write the app's selection into ignore lines with ancestor lines (mirror `engine/ignorepattern.go`), start Syncthing with `downloadJob` true so the gate opens, end the job by stopping Syncthing, and start it again with the gate closed if the app is still on screen. Then: Download now as a UIDT job that arms only its file on a network the setting does not allow; WorkManager work for automatic updates with the sync network constraint; deselect in the order ignore, scan, delete; the progress notification with pause and stop. Extend `dev/e2e-emulator.sh` to check each. Also check on the device the free-space lookup on app storage, and that a job's transfer uses the job's network. After that: `FileProvider` and VLC, then the tree cache.
