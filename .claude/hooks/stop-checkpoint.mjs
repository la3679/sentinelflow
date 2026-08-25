#!/usr/bin/env node
/**
 * Stop hook - checkpoint reminder.
 *
 * Asks for one continuation when the repository has meaningful uncommitted work
 * and PROJECT_STATE.md has not been touched to reflect it. The point is to stop
 * a session ending with real progress that exists only in someone's context
 * window.
 *
 * Recursion protection, in three independent layers, because a Stop hook that
 * loops is worse than no Stop hook at all:
 *
 *   1. `stop_hook_active` is true whenever Claude Code is already continuing
 *      because of a stop hook. This hook never blocks twice in a row.
 *   2. A marker file per session in the git-ignored .claude/runtime/ means the
 *      reminder fires at most once per session, even across restarts of this
 *      script.
 *   3. Claude Code itself overrides a stop hook after 8 consecutive blocks.
 *
 * It never commits, stages, or pushes anything. It asks; the operator decides.
 */

import { existsSync } from "node:fs";
import { join } from "node:path";

import {
  emitNothing,
  gitSnapshot,
  readHookInput,
  repoRoot,
  runtimeDir,
  significantChanges,
  writeRuntimeFile,
} from "./lib.mjs";

const input = await readHookInput();

// Layer 1: already continuing because of a stop hook.
if (input.stop_hook_active === true) emitNothing();

const root = repoRoot(input.cwd ?? process.cwd());
if (!root) emitNothing();

// Layer 2: at most one reminder per session.
const sessionId = String(input.session_id ?? "unknown").replace(/[^A-Za-z0-9_-]/g, "");
const dir = runtimeDir(root);
const marker = dir ? join(dir, `checkpoint-reminded-${sessionId}`) : null;
if (marker && existsSync(marker)) emitNothing();

const snapshot = gitSnapshot(root);
const significant = significantChanges(snapshot.dirtyPaths);

// Nothing meaningful is uncommitted, so there is nothing to remind about.
if (significant.length === 0) emitNothing();

// Work is uncommitted but the state document was also touched: the operator is
// already mid-checkpoint. Staying quiet is correct.
const stateTouched = snapshot.dirtyPaths.some(
  (p) => p === "PROJECT_STATE.md" || p === "docs/planning/SESSION_LOG.md",
);
if (stateTouched) emitNothing();

if (marker) writeRuntimeFile(root, `checkpoint-reminded-${sessionId}`, new Date().toISOString());

const preview = significant.slice(0, 8).join(", ");
const more = significant.length > 8 ? `, and ${significant.length - 8} more` : "";

process.stdout.write(
  JSON.stringify({
    decision: "block",
    reason:
      `${significant.length} path(s) are uncommitted and PROJECT_STATE.md has not been ` +
      `updated to reflect them: ${preview}${more}. ` +
      "Run the checkpoint before finishing: inspect `git status` and both diffs, run " +
      "`git diff --check`, run the smallest relevant format/lint/type/test checks, update " +
      "PROJECT_STATE.md with real progress and exact next actions, append to " +
      "docs/planning/SESSION_LOG.md, commit with a Conventional Commit message, push, and " +
      "verify the remote SHA. " +
      "If the work is genuinely incomplete and cannot be committed safely, say so plainly and " +
      "record the exact incompleteness in PROJECT_STATE.md instead - do not pretend it is done. " +
      "This reminder fires once per session and will not repeat.",
  }),
);
process.exit(0);
