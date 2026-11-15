---
title: Design automatic background sync (after the first release)
assignee: together
depends_on: []
touches:
  - docs/decisions.md
---

## Goal
It is decided how periodic automatic sync works without opening the app and without keeping it running in the background. Automatic updates noticed while the app is open are already in the first release (`docs/decisions.md`, flow 5a).

## Steps
- [ ] Decided how sync runs while charging and on Wi-Fi, aiming for once every 6 hours.
- [ ] Decided what happens when a large transfer does not fit into a background run.

## Done
- Periodic background sync comes after the first release (`docs/decisions.md`, "Automatic background sync"). This task covers only periodic checks without opening the app.

## Next
Do not start before the first release. Then discuss the design with the user and add a separate implementation task.
