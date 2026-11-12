---
title: Add basic CI checks for the public repository
assignee: agent
depends_on: []
touches:
  - .github/
  - .gitignore
  - dev/
  - docs/ci.md
  - docs/README.md
  - AGENTS.md
outcome:
---

## Goal
Every push and pull request is checked for leaked secrets, files that must not be public, and workflow problems, using the same approach as Subyard: pinned tools, pinned actions, all errors reported at once.

## Acceptance
- [x] `bash dev/check.sh` passes on a clean tree.
- [x] Planted violations are reported together: a GitHub token, a `.pem` file, Cyrillic text and a development machine path.
- [ ] The workflow passes on GitHub after the user commits and pushes.
- [ ] The user has checked that secret scanning, push protection and Dependabot alerts are on in the repository settings. The agent's GitHub App cannot read these settings.

## Next
User: commit, push, and check the first CI run and the repository security settings.

## Log
- 2026-11-12T23:20Z claude-opus: added `ci.yml`, `dependabot.yml`, `dev/` check scripts and `docs/ci.md`. Local run passes; planted violations were all reported in one run.
