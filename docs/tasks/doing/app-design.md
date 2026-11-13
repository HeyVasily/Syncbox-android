---
title: Develop the app design
assignee: together
depends_on: []
touches:
  - docs/design/**
---

## Goal
The screens, flows and visual style of the first release are designed and agreed before implementation starts.

## Acceptance
- [x] The first-release user flows are documented, including pairing, connecting and refreshing the file list on app open, file selection, starting sync with a button, the sync notification with progress, stop and pause, and opening a video.
- [ ] The user approves the design.

## Next
Get the user's approval of `docs/design/flows.md`. Then make 2-3 HTML mockup variants of the visual style, let the user choose, and record the approved style in `docs/design/`.

## Log
- 2026-11-02T00:00Z claude-opus: created.
- 2026-11-13T00:00Z claude-opus: claimed at the user's request. Process agreed: flows as text first, then HTML mockup variants. Wrote the draft `docs/design/flows.md` with 9 open questions.
- 2026-11-13T00:30Z claude-opus: user answered all 9 questions; flows updated: many devices, several folders, mobile data setting, three sort orders, finished notification disappears after 3 seconds.
- 2026-11-13T01:00Z claude-opus: user decisions: sync network setting (off, Wi-Fi, mobile except roaming, any) limits only the Sync button; list refresh works on any network; single-file download on any network; offline mode toggle. Automatic sync stays after the first release.
