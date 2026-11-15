---
title: Agree on and build the verification setup
assignee: together
depends_on: []
touches:
  - docs/verification.md
---

## Goal
An agent can build the app and check it end to end on its own, using the yard's emulator and a Syncthing peer. This setup is not a project deliverable.

## Steps
- [ ] The approach is agreed with the user and documented in `docs/verification.md`.
- [x] An agent can run the app on the emulator and reach a Syncthing peer.
- [ ] An agent can check opening a file in VLC on the emulator.
- [ ] A 16 KB emulator image and a 4 KB API 34 image are available, and `getconf PAGE_SIZE` is confirmed on each.
- [ ] The plan for ARM64 testing before release is agreed, since x86-64 images only simulate 16 KB.
- [x] The toolchain builds an app with a gomobile library, and the 16 KB alignment checks run on it.

## Done
- `docs/verification.md` describes the levels: host Go tests (`dev/test-engine.sh`), the app build (`dev/build-app.sh`), the installed app with the real engine and a host peer on the emulator (`dev/e2e-emulator.sh`), and demo screenshots (`dev/screenshots.sh`).
- `dev/build-bridge.sh` and `dev/build-app.sh` build the gomobile bridge and the APKs and fail on 16 KB ELF or ZIP misalignment (#11).
- Emulators are leased from `android-broker`. Its API 36 x86_64 image has 4 KB pages; its catalog has no 16 KB image.

## Next
Agree the documented approach with the user. Find out whether the broker can provide a 16 KB image and a 4 KB API 34 image, and agree the ARM64 test plan. The VLC check comes with `end-to-end-prototype`.
