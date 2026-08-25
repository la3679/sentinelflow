#!/usr/bin/env node
/**
 * PreCompact and SessionEnd hook - write an emergency Git snapshot.
 *
 * Both events mean "context is about to be lost", so both want the same thing:
 * a durable, git-ignored record of exactly where the repository stood, written
 * without touching the repository itself.
 *
 * This is not a substitute for PROJECT_STATE.md. It records mechanical facts -
 * branch, HEAD, dirty paths, recent commits. It cannot record semantic progress,
 * and it does not try to: only Claude can write that, which is why PreCompact
 * also returns a reminder to do so before the compaction happens.
 *
 * It writes one file under .claude/runtime/ and nothing else. No commit, no
 * stage, no push, no delete, no network call, and it never opens .env.
 *
 * Usage: node snapshot.mjs <PreCompact|SessionEnd>
 */

import {
  emitContext,
  emitNothing,
  gitSnapshot,
  readHookInput,
  repoRoot,
  writeRuntimeFile,
} from "./lib.mjs";

const event = process.argv[2] === "PreCompact" ? "PreCompact" : "SessionEnd";
const input = await readHookInput();
const root = repoRoot(input.cwd ?? process.cwd());

if (!root) emitNothing();

const snapshot = gitSnapshot(root);
const timestamp = new Date().toISOString();

const record = {
  event,
  timestamp_utc: timestamp,
  // `reason` on SessionEnd, `trigger` on PreCompact. Both are informational.
  reason: input.reason ?? input.trigger ?? null,
  session_id: input.session_id ?? null,
  branch: snapshot.branch,
  head: snapshot.head,
  upstream: snapshot.upstream,
  upstream_head: snapshot.upstreamHead,
  local_matches_upstream: Boolean(snapshot.head) && snapshot.head === snapshot.upstreamHead,
  dirty_paths: snapshot.dirtyPaths,
  recent_commits: snapshot.recentCommits,
  note:
    "Machine-generated mechanical snapshot. It records where Git stood, not what was " +
    "achieved. PROJECT_STATE.md is the authoritative semantic record.",
};

// One file per event type, overwritten each time: the useful question is
// "where was I when this last happened", not a growing pile of history.
const filename = event === "PreCompact" ? "pre-compact-snapshot.json" : "session-end-snapshot.json";
const written = writeRuntimeFile(root, filename, `${JSON.stringify(record, null, 2)}\n`);

// SessionEnd has no decision control and shares a short budget across hooks:
// write the file and get out of the way.
if (event === "SessionEnd") emitNothing();

const dirtyNote =
  snapshot.dirtyPaths.length > 0
    ? `${snapshot.dirtyPaths.length} path(s) are uncommitted. `
    : "The working tree is clean. ";

emitContext(
  "PreCompact",
  "Context is about to be compacted. " +
    dirtyNote +
    `Branch ${snapshot.branch} at ${snapshot.headShort ?? "unknown"}. ` +
    (written ? `A mechanical snapshot was written to ${filename} under .claude/runtime/. ` : "") +
    "That file records Git state only. Before the compaction takes effect, update " +
    "PROJECT_STATE.md with the semantic progress and the exact next actions - no script can " +
    "reconstruct those from a diff.",
);
