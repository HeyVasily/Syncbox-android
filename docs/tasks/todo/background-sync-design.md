---
title: Design automatic background sync (after the first release)
assignee: together
depends_on: []
touches:
  - docs/decisions.md
---

## Goal
It is decided how periodic automatic sync works without opening the app and without keeping it running in the background. Automatic updates noticed while the app is open are already in the first release (`docs/decisions.md`, flow 5a).

## Acceptance
- [ ] Decided how sync runs while charging and on Wi-Fi, aiming for once every 6 hours.
- [ ] Decided what happens when a large transfer does not fit into a background run.

## Next
Do not start before the first release. Then discuss the design with the user and add a separate implementation task.

## Log
- 2026-11-02T00:00Z claude-opus: created.
- 2026-11-02T00:00Z claude-opus: the user moved background sync to after the first release.
- 2026-11-14T06:30Z claude-opus: the user moved automatic update downloads, noticed while the app is open, into the first release; this task now covers only periodic checks without opening the app.
