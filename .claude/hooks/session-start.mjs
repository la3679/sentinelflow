#!/usr/bin/env node
/**
 * SessionStart hook.
 *
 * Injects the current Git facts and the resume procedure into context at the
 * start of every session, and again after a compaction - the `compact` matcher
 * fires when auto or manual compaction produces a fresh context.
 *
 * This is deliberately where the post-compaction reminder lives. PostCompact
 * exists as an event but has no decision control in the current hook schema:
 * it can log, it cannot add context. SessionStart with matcher `compact` is
 * the supported way to put PROJECT_STATE.md back in front of Claude after a
 * compaction, so that is what is configured.
 *
 * Read-only. It runs git queries and prints; it changes nothing.
 */

import {
  emitContext,
  emitNothing,
  git,
  gitSnapshot,
  readHookInput,
  repoRoot,
  significantChanges,
} from "./lib.mjs";

const input = await readHookInput();
const cwd = input.cwd ?? process.cwd();
const root = repoRoot(cwd);

if (!root) {
  // Not inside a Git repository. Say so loudly: the working-directory
  // invariant is that all work happens inside the Lovable-created clone, and
  // silently continuing in a temporary folder is exactly the failure this
  // guards against.
  emitContext(
    "SessionStart",
    "This directory is not inside a Git repository. SentinelFlow work must happen " +
      "inside the clone of https://github.com/la3679/sentinelflow.git. Verify with " +
      "`git rev-parse --show-toplevel` before editing anything.",
  );
}

const snapshot = gitSnapshot(root);

// The working-directory invariant: all implementation happens in the clone of
// the Lovable-created repository. A second clone or a stray `git init` is the
// failure mode this catches, and it is cheap to check every session.
const EXPECTED_ORIGIN = "la3679/sentinelflow";
const originUrl = git(root, ["remote", "get-url", "origin"]);
const originMatches = originUrl !== null && originUrl.includes(EXPECTED_ORIGIN);

const lines = [];
const source = input.source ?? input.matcher ?? "startup";

if (source === "compact") {
  lines.push(
    "Context was just compacted. Re-read PROJECT_STATE.md before continuing - the " +
      "summary you now hold is not a substitute for it.",
  );
} else {
  lines.push("SentinelFlow session start. Read CLAUDE.md and PROJECT_STATE.md before editing.");
}

lines.push("");
lines.push("Git state, read just now (not from memory):");
if (originMatches) {
  lines.push(`- origin: ${originUrl}`);
} else {
  lines.push(
    `- origin: ${originUrl ?? "none"} - THIS IS NOT ${EXPECTED_ORIGIN}. Stop and confirm ` +
      "you are in the right clone before editing anything.",
  );
}
lines.push(`- branch: ${snapshot.branch}`);
lines.push(`- HEAD: ${snapshot.headShort ?? "unknown"}`);

if (snapshot.upstream) {
  const inSync = snapshot.head && snapshot.head === snapshot.upstreamHead;
  lines.push(
    `- upstream: ${snapshot.upstream} (${inSync ? "in sync with local" : "DIFFERS from local - fetch and compare before editing"})`,
  );
} else {
  lines.push("- upstream: none - this branch has never been pushed");
}

const significant = significantChanges(snapshot.dirtyPaths);
if (significant.length === 0) {
  lines.push("- working tree: clean");
} else {
  lines.push(`- working tree: ${significant.length} uncommitted path(s)`);
  for (const path of significant.slice(0, 10)) lines.push(`    ${path}`);
  if (significant.length > 10) lines.push(`    ... and ${significant.length - 10} more`);
  lines.push("  Inspect these before editing. Never delete unexpected work.");
}

if (snapshot.recentCommits.length > 0) {
  lines.push("");
  lines.push("Recent commits:");
  for (const commit of snapshot.recentCommits) lines.push(`- ${commit}`);
}

lines.push("");
lines.push(
  "Then continue from 'Next three actions' in PROJECT_STATE.md. If the repository " +
    "contradicts that file, trust Git and the files, correct the document, and note the " +
    "discrepancy in docs/planning/SESSION_LOG.md.",
);

emitContext("SessionStart", lines.join("\n"));
emitNothing();
