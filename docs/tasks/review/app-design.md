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
- [x] The user approves the design.

## Next
Finished: the user approved the design on 2026-11-14. Delete this file when the user confirms.

## Log
- 2026-11-02T00:00Z claude-opus: created.
- 2026-11-13T00:00Z claude-opus: claimed at the user's request. Process agreed: flows as text first, then HTML mockup variants. Wrote the draft `docs/design/flows.md` with 9 open questions.
- 2026-11-13T00:30Z claude-opus: user answered all 9 questions; flows updated: many devices, several folders, mobile data setting, three sort orders, finished notification disappears after 3 seconds.
- 2026-11-13T01:00Z claude-opus: user decisions: sync network setting (off, Wi-Fi, mobile except roaming, any) limits only the Sync button; list refresh works on any network; single-file download on any network; offline mode toggle. Automatic sync stays after the first release.
- 2026-11-13T02:30Z claude-opus: made three visual variants of the Files screen, idle and syncing, for the user to choose from.
- 2026-11-13T03:30Z claude-opus: user picked variant A, asked for light and dark themes, a proper settings icon, more screens and five more variants focused on control placement. Rebuilt the canvas accordingly.
- 2026-11-14T00:00Z claude-opus: user chose the control layout: variant A plus All files / On device tabs plus a per-file menu. Recorded in `docs/design/ui.md`, flows updated.
- 2026-11-14T01:00Z claude-opus: redrew the canvas in the chosen layout, added the missing screens, removed the rejected layout options.
- 2026-11-14T01:30Z claude-opus: saved the mockups as standalone HTML in `docs/design/mockups/` at the user's request. Rendered with headless Chromium and checked: all four files display correctly in both themes.
- 2026-11-14T02:30Z claude-opus: the user approved the design ("design approved"). Evidence: flows in `docs/design/flows.md`, style and layout in `docs/design/ui.md`, mockups in `docs/design/mockups/` rendered and checked. Moved to `review/`.
