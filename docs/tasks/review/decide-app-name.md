---
title: Decide the app name
assignee: together
depends_on: []
touches:
  - docs/decisions.md
---

## Goal
A final app name and `applicationId` are chosen before the first Google Play release.

## Acceptance
- [ ] The name is checked against the USPTO, EUIPO and WIPO trademark registers (classes 9 and 42).
- [ ] The name is checked against Google Play and web search results.
- [ ] The name and `applicationId` are recorded in `docs/decisions.md`.

## Next
Superseded by `rename-to-avoska`: the user chose "Avoska". Delete this file when the user confirms.

## Log
- 2026-11-02T00:00Z claude-opus: created from the initial name check; trademark registers were not checked.
- 2026-11-14T01:59Z claude-fable: the user chose "Avoska". The register checks and the `applicationId` moved to the `rename-to-avoska` task, so this task is superseded and waits for the user to drop it.
