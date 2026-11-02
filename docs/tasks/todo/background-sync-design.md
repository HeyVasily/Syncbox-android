---
title: Design automatic background sync (after the first release)
assignee: together
depends_on: []
touches:
  - docs/decisions.md
outcome:
---

## Goal
It is decided how automatic sync works without keeping the app running in the background.

## Acceptance
- [ ] Decided how sync runs while charging and on Wi-Fi, aiming for once every 6 hours.
- [ ] Decided what happens when a large transfer does not fit into a background run.

## Next
Do not start before the first release. Then discuss the design with the user and add a separate implementation task.

## Log
- 2026-11-02T00:00Z claude-opus: created.
- 2026-11-02T00:00Z claude-opus: the user moved background sync to after the first release.
