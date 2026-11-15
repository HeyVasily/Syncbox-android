---
title: Research 16 KB memory page support for embedded Syncthing
assignee: user
depends_on: []
touches:
  - docs/research/16kb-page-size.md
---

## Goal
Know exactly what is needed for an embedded Go Syncthing library to meet the Google Play 16 KB memory page requirement.

## Steps
- [x] The research report is saved in `docs/research/16kb-page-size.md`.
- [x] Its conclusions are reflected in `docs/decisions.md`.
- [ ] The user accepts the conclusions.

## Done
- The report comes from external research. Its key claims are checked against primary sources; its experiments are not reproduced. `check_native_16k.py` and the report appendices are not in the repository.
- The conclusions are in `docs/decisions.md` under "Proposed": toolchain, ABIs, 16 KB checks, 16 KB runtime testing. The build-time 16 KB checks run in `dev/build-bridge.sh` and `dev/build-app.sh` (#11).

## Next
Wait for the user's review. Decide whether to bring `check_native_16k.py` and the report appendices into the repository.
