# Claude Code configuration

SentinelFlow ships a project-scoped Claude Code configuration in `.claude/`: a
status line, four hooks, a rules directory, and a checkpoint helper. This
document explains what each one does, how to debug it, and how to turn it off.

**None of this is required to build or run SentinelFlow.** Delete `.claude/` and
every `make` target still works. It exists because the project is developed with
Claude Code and the build standards require the context-usage percentage to be
visible and the checkpoint protocol to be enforced rather than remembered.

## Prerequisites

Every script here is JavaScript run by **Bun**, which is already a prerequisite
(`make bootstrap` checks for it). Two reasons for JavaScript rather than shell:

- The reference machine is Windows. A bash status line is one more thing that
  can be missing, and a PowerShell one is one more thing to keep in sync.
- Parsing the session JSON in shell needs `jq`, which is not a prerequisite of
  this repository and which the build standards explicitly say must not be
  assumed.

## What is configured

| File                                | Purpose                                                           |
| ----------------------------------- | ----------------------------------------------------------------- |
| `.claude/settings.json`             | Status line and hook registration                                 |
| `.claude/statusline.mjs`            | Renders the status line                                           |
| `.claude/hooks/lib.mjs`             | Shared, read-only Git helpers                                     |
| `.claude/hooks/session-start.mjs`   | Injects verified Git state at session start and after compaction  |
| `.claude/hooks/stop-checkpoint.mjs` | Asks for a checkpoint when state has gone stale                   |
| `.claude/hooks/snapshot.mjs`        | Writes an emergency snapshot before compaction and at session end |
| `.claude/rules/`                    | Per-language and workflow rules referenced by `CLAUDE.md`         |
| `scripts/claude/checkpoint*`        | Gathers the mechanical facts a checkpoint needs                   |
| `.claude/runtime/`                  | Git-ignored machine state written by the above                    |

## Status line

Two lines. The first carries the model, session name, directory, branch,
ahead/behind counts, and a dirty-file count. The second is a context-usage bar
coloured by the checkpoint thresholds from `CLAUDE.md`:

| Used   | Colour | Label                  |
| ------ | ------ | ---------------------- |
| 0–69%  | green  | —                      |
| 70–79% | yellow | `finish current unit`  |
| 80–84% | red    | `CHECKPOINT NOW`       |
| 85%+   | red    | `EMERGENCY CHECKPOINT` |

The percentage is genuinely absent early in a session and again after `/compact`
until the next API call, so the bar renders `ctx --` rather than a misleading
zero.

Rate-limit usage, when the session exposes it, is shown separately and labelled
`5h limit`. **It is a different thing from context usage** and the two must not
be confused — one governs when to checkpoint, the other governs nothing.

Every render also writes `.claude/runtime/context.json`, which is how
`scripts/claude/checkpoint` can report the last known percentage. Claude Code
passes that number to the status line and nowhere else.

### Debug it

```bash
echo '{"model":{"display_name":"Opus"},"cwd":".","context_window":{"used_percentage":81,"remaining_percentage":19}}' \
  | bun .claude/statusline.mjs
```

## Hooks

All four are **read-only with respect to the repository**. They never commit,
stage, push, delete, or fetch. The only files they write are under the
git-ignored `.claude/runtime/`. None of them opens `.env`.

### `SessionStart` — matcher `startup|resume|clear|compact`

Runs `git` and injects what it found: the origin URL (with a loud warning if it
is not `la3679/sentinelflow`), branch, HEAD, whether local matches upstream,
uncommitted paths, and the last five commits. On the `compact` matcher it leads
with a reminder to re-read `PROJECT_STATE.md`.

**Why the post-compaction reminder lives here rather than in `PostCompact`:**
`PostCompact` exists as an event, but in the current hook schema it has no
decision control — it can log, it cannot add context. `SessionStart` with the
`compact` matcher is the supported way to put a document back in front of Claude
after a compaction, so that is what is configured. This is a deliberate
deviation from a literal reading of the build standards, made to follow the
actual schema.

### `Stop` — checkpoint reminder

Blocks the end of a turn **at most once per session** when the repository has
meaningful uncommitted work and neither `PROJECT_STATE.md` nor
`docs/planning/SESSION_LOG.md` has been touched to reflect it.

Build output, `.env`, `node_modules`, `target/`, `.venv/` and coverage
directories are excluded. A dirty coverage report is not a reason to ask anyone
to checkpoint.

Recursion protection, in three independent layers, because a looping `Stop` hook
is worse than no hook at all:

1. `stop_hook_active` is `true` whenever Claude Code is already continuing
   because of a stop hook. The hook returns silently in that case.
2. A per-session marker file in `.claude/runtime/` means the reminder fires once
   per session even if the script is invoked again.
3. Claude Code itself overrides a stop hook after 8 consecutive blocks.

### `PreCompact` — matcher `manual|auto`

Writes `.claude/runtime/pre-compact-snapshot.json` and returns a reminder that
the snapshot records Git state only, so the semantic progress must be written
into `PROJECT_STATE.md` before the compaction takes effect.

### `SessionEnd`

Writes `.claude/runtime/session-end-snapshot.json` and returns nothing.
`SessionEnd` hooks share a short time budget, so this one does the minimum and
gets out of the way.

### Debug a hook

Every hook reads JSON on stdin, so each can be run by hand:

```bash
echo '{"cwd":"'"$PWD"'","hook_event_name":"SessionStart","source":"startup"}' \
  | bun .claude/hooks/session-start.mjs

echo '{"cwd":"'"$PWD"'","hook_event_name":"Stop","session_id":"test","stop_hook_active":false}' \
  | bun .claude/hooks/stop-checkpoint.mjs

echo '{"cwd":"'"$PWD"'","hook_event_name":"PreCompact","trigger":"manual"}' \
  | bun .claude/hooks/snapshot.mjs PreCompact
```

To see what Claude Code itself is doing with them, start it with `--debug`.

### Disable them

- **One hook:** delete its block from `.claude/settings.json`.
- **All of them:** delete the `hooks` key, or delete `.claude/settings.json`.
- **Just the Stop reminder, for one session:** create the marker by hand —
  `touch .claude/runtime/checkpoint-reminded-<session_id>`.
- **Everything, permanently:** delete `.claude/`. Nothing else depends on it.

Project settings can also be overridden by a personal
`.claude/settings.local.json`, which is git-ignored.

## Checkpoint helper

```bash
scripts/claude/checkpoint             # human-readable
scripts/claude/checkpoint --json      # machine-readable
scripts/claude/checkpoint --write     # also write .claude/runtime/checkpoint.json
```

```powershell
.\scripts\claude\checkpoint.ps1
```

It reports branch, HEAD, upstream and whether they match, uncommitted files,
`git diff --check` problems, recent commits, the open pull request, the latest
CI run per workflow, and the last known context percentage.

It deliberately does **not** fetch, and it deliberately does **not** judge
whether the work is finished. It cannot read a diff and know whether a feature
is complete or what the next three actions should be. Those sections of
`PROJECT_STATE.md` are written by hand, every time.

`gh` is optional. Without it, the Git facts still print and the GitHub section
says the state is unknown rather than pretending it is fine.

## Runtime directory

`.claude/runtime/` is git-ignored and holds only machine-generated state:

| File                        | Written by           | Contents                      |
| --------------------------- | -------------------- | ----------------------------- |
| `worktree.json`             | set up by hand       | The absolute clone path       |
| `context.json`              | status line          | Last context percentage       |
| `pre-compact-snapshot.json` | `PreCompact`         | Git state before a compaction |
| `session-end-snapshot.json` | `SessionEnd`         | Git state at session end      |
| `checkpoint.json`           | `checkpoint --write` | Full checkpoint fact set      |
| `checkpoint-reminded-*`     | `Stop`               | Per-session recursion marker  |

Deleting any of them is safe. They are regenerated, and none is an input to a
build.

## Schema note

The hook and status-line schemas here were verified against the Claude Code
documentation on 2026-08-25 and the behaviour was exercised locally before being
committed — see `docs/planning/SESSION_LOG.md`. If a future Claude Code release
changes either schema, re-verify before trusting these, and record what changed
in `docs/research/RESEARCH_LOG.md`.
