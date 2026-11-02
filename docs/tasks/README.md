# Tasks

Each task is one Markdown file. The folder holding the file is the task's status, so `ls docs/tasks/*` shows the state of the project. Everything an agent needs to resume after a stop is in the task file.

## Status folders

| Folder | Meaning |
|---|---|
| `todo/` | Not started |
| `doing/` | An agent is working on it |
| `blocked/` | Cannot continue; the task's `Next` section says why |
| `review/` | Finished, waiting for the user to accept |
| `done/` | Accepted by the user, or dropped (see `outcome`) |

## Task file

The file name is the task ID: a unique, descriptive kebab-case slug such as `app-design.md`. Slugs have no numbers, so parallel agents cannot collide on the next number.

```markdown
---
title: Short task title
assignee: agent        # agent | user | together
depends_on: []         # slugs that must be in done/
touches: []            # paths or globs this task will edit
outcome:               # completed | dropped, set when moved to done/
---

## Goal
What must be true when this task is finished.

## Acceptance
- [ ] A check that can be verified, with the command or method.

## Next
The exact next action, written so a new agent can continue from here. Replace this text as work moves on.

## Log
- 2026-11-02T10:00Z agent-name: what was done or learned. Append only.
```

## Working on a task

1. **Pick:** choose a task in `todo/` with `assignee: agent`, all `depends_on` in `done/`, and `touches` that do not overlap any task in `doing/`. Agents do not pick `user` or `together` tasks unless asked.
2. **Claim:** run `mv docs/tasks/todo/<slug>.md docs/tasks/doing/<slug>.md`. A rename is atomic, so one agent wins. If it fails, another agent got there first: pick a different task.
3. **Confirm:** add a Log entry with your agent name. Check `doing/` again, and if `touches` now overlaps another task, move yours back to `todo/` and pick again.
4. **Checkpoint:** after every meaningful step, tick `Acceptance`, rewrite `Next`, and append to `Log`. Do this before starting anything long, because a stop can happen at any moment.
5. **Finish:** run the checks, record the evidence in `Log`, and move the file to `review/`. Only the user moves tasks to `done/`, unless the user asks an agent to do it.

If stuck, write the reason and the question in `Next` and move the file to `blocked/`. If new work appears, add a new file to `todo/` instead of growing the current task.

## Resuming after a stop

- **Your own interrupted task:** read the file and continue from `Next`. Check the working tree first, because the last step may be partly done.
- **Someone else's task:** take it over only if the user asks, or if the file has not changed for 2 hours. Run `mv docs/tasks/doing/<slug>.md docs/tasks/todo/<slug>.md`, then claim it as usual. If the move fails, another agent took it first.

## Rules

- Only the agent that claimed a task edits its file.
- Parallel agents share one working tree on `master`, so non-overlapping `touches` are what keep them apart. Shared build output can still collide, so run builds that could interfere one at a time.
- Agents do not commit or push unless the user asks. The task files record progress on disk; they are protected from loss of the machine only once the user commits them.
- Claims work only for agents sharing the same filesystem.
