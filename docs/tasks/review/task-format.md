---
title: Define the task format
assignee: agent
depends_on: []
touches:
  - docs/tasks/README.md
---

## Goal
Agents can update tasks during implementation, progress is visible on disk after a stop, and parallel agents do not get in each other's way.

## Acceptance
- [x] The format and the working rules are documented in `docs/tasks/README.md`.
- [x] Claiming is atomic: a race test ran 200 rounds with 4 competing `mv` processes.
- [ ] The user accepts the format.

## Next
Wait for the user's review. Adjust the format based on their feedback.

## Log
- 2026-11-02T00:00Z claude-opus: replaced the git branch claim idea with status folders, because work happens on `master` and agents do not commit.
