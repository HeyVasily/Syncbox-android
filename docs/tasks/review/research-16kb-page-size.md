---
title: Research 16 KB memory page support for embedded Syncthing
assignee: user
depends_on: []
touches:
  - docs/research/16kb-page-size.md
---

## Goal
Know exactly what is needed for an embedded Go Syncthing library to meet the Google Play 16 KB memory page requirement.

## Acceptance
- [x] The research report is saved in `docs/research/16kb-page-size.md`.
- [x] Its conclusions are reflected in `docs/decisions.md`.
- [ ] The user accepts the conclusions.

## Next
Wait for the user's review. Decide whether to bring `check_native_16k.py` and the report appendices into the repository.

## Log
- 2026-11-02T00:00Z claude-opus: research prompt given to the user.
- 2026-11-02T00:00Z claude-opus: saved the external agent's report. Checked key claims against primary sources; the experiments were not reproduced. `check_native_16k.py` and the appendices are not available here.
