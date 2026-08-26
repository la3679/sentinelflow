#!/usr/bin/env node
/**
 * Gather the mechanical facts a checkpoint needs.
 *
 * What it does: reads Git state, the open pull request, the latest CI runs, and
 * the last context percentage the status line recorded, then prints them.
 *
 * What it deliberately does not do: decide whether the work is finished. It
 * cannot read a diff and know whether a feature is complete, whether a test was
 * meaningful, or what the next three actions should be. Claude writes the
 * semantic sections of PROJECT_STATE.md; this only supplies the facts that
 * would otherwise be typed from memory and get typed wrong.
 *
 * It never mutates the repository: no commit, no stage, no push, no delete, no
 * fetch. The only file it may write is git-ignored, under .claude/runtime/.
 *
 *   node scripts/claude/checkpoint.mjs            human-readable
 *   node scripts/claude/checkpoint.mjs --json     machine-readable
 *   node scripts/claude/checkpoint.mjs --write    also write the runtime file
 *
 * Windows PowerShell without a Node on PATH: bun scripts/claude/checkpoint.mjs
 */

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = run("git", ["rev-parse", "--show-toplevel"], HERE) ?? process.cwd();

const args = new Set(process.argv.slice(2));
const asJson = args.has("--json");
const shouldWrite = args.has("--write");

/** Run a command and return trimmed stdout, or null on any failure. */
function run(command, commandArgs, cwd = ROOT, timeout = 20000) {
  try {
    return execFileSync(command, commandArgs, {
      cwd,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
      timeout,
    }).trim();
  } catch {
    return null;
  }
}

function lines(value) {
  return (value ?? "").split("\n").filter(Boolean);
}

// ---------------------------------------------------------------------------
// Git - local only. No fetch: a checkpoint helper must not change what the
// working copy knows about the remote behind the operator's back.
// ---------------------------------------------------------------------------
const branch = run("git", ["branch", "--show-current"]) || "(detached)";
const head = run("git", ["rev-parse", "HEAD"]);
const upstream = run("git", ["rev-parse", "--abbrev-ref", "@{upstream}"]);
const upstreamHead = upstream ? run("git", ["rev-parse", "@{upstream}"]) : null;

// Parsed by shape, not by fixed offset. `run` trims its output, which eats the
// leading space of an unstaged-only status line (" M path") - but only on the
// first line, because the rest keep theirs after the split. Slicing at columns
// 0-2 and 3 therefore dropped the first character of the first path and left
// every other one intact, which is the kind of defect that reads as correct
// until the one file you care about is listed first.
const porcelain = run("git", ["status", "--porcelain"]) ?? "";
const dirty = lines(porcelain).flatMap((line) => {
  const match = /^(.{1,2}?)\s(.*)$/.exec(line);
  return match ? [{ status: match[1].trim(), path: match[2].trim() }] : [];
});

const whitespaceIssues = run("git", ["diff", "--check"]);
const stagedWhitespaceIssues = run("git", ["diff", "--cached", "--check"]);

// ---------------------------------------------------------------------------
// GitHub - only if gh is installed and authenticated. Absence is reported, not
// treated as an error: a checkpoint must work offline.
// ---------------------------------------------------------------------------
const ghAuthenticated = run("gh", ["auth", "status"]) !== null;

let pullRequest = null;
let ciRuns = [];
if (ghAuthenticated) {
  const prJson = run("gh", [
    "pr",
    "list",
    "--head",
    branch,
    "--state",
    "open",
    "--json",
    "number,url,title,isDraft,mergeable,reviewDecision",
  ]);
  try {
    const parsed = prJson ? JSON.parse(prJson) : [];
    pullRequest = parsed[0] ?? null;
  } catch {
    pullRequest = null;
  }

  const runsJson = run("gh", [
    "run",
    "list",
    "--branch",
    branch,
    "--limit",
    "10",
    "--json",
    "name,status,conclusion,headSha,createdAt",
  ]);
  try {
    ciRuns = runsJson ? JSON.parse(runsJson) : [];
  } catch {
    ciRuns = [];
  }
}

// Only the newest run per workflow name matters; older ones are superseded.
const latestByWorkflow = new Map();
for (const entry of ciRuns) {
  if (!latestByWorkflow.has(entry.name)) latestByWorkflow.set(entry.name, entry);
}
const workflows = [...latestByWorkflow.values()];

// ---------------------------------------------------------------------------
// Last known context percentage, if the status line recorded one.
// ---------------------------------------------------------------------------
let contextPercent = null;
const contextFile = join(ROOT, ".claude", "runtime", "context.json");
if (existsSync(contextFile)) {
  try {
    contextPercent = JSON.parse(readFileSync(contextFile, "utf8")).used_percentage ?? null;
  } catch {
    contextPercent = null;
  }
}

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------
const report = {
  timestamp_utc: new Date().toISOString(),
  repository_root: ROOT,
  branch,
  head,
  upstream,
  upstream_head: upstreamHead,
  local_matches_upstream: Boolean(head) && head === upstreamHead,
  dirty_files: dirty,
  whitespace_issues: Boolean(whitespaceIssues) || Boolean(stagedWhitespaceIssues),
  recent_commits: lines(run("git", ["log", "--oneline", "-10"])),
  gh_authenticated: ghAuthenticated,
  pull_request: pullRequest,
  workflows,
  last_known_context_percent: contextPercent,
};

if (shouldWrite) {
  try {
    mkdirSync(join(ROOT, ".claude", "runtime"), { recursive: true });
    writeFileSync(
      join(ROOT, ".claude", "runtime", "checkpoint.json"),
      `${JSON.stringify(report, null, 2)}\n`,
      "utf8",
    );
  } catch {
    // Not fatal. The report still prints.
  }
}

if (asJson) {
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  process.exit(0);
}

const out = [];
out.push("SentinelFlow checkpoint facts");
out.push(`  time (UTC)     ${report.timestamp_utc}`);
out.push(`  branch         ${branch}`);
out.push(`  HEAD           ${head ?? "unknown"}`);

if (upstream) {
  out.push(
    `  upstream       ${upstream} ${report.local_matches_upstream ? "(in sync)" : "(DIFFERS - push or pull before claiming pushed)"}`,
  );
} else {
  out.push("  upstream       none - this branch has never been pushed");
}

out.push(`  context used   ${contextPercent === null ? "unknown" : `${contextPercent}%`}`);
out.push("");

if (dirty.length === 0) {
  out.push("Working tree: clean");
} else {
  out.push(`Working tree: ${dirty.length} uncommitted path(s)`);
  for (const file of dirty) out.push(`  ${file.status.padEnd(2)} ${file.path}`);
}

if (report.whitespace_issues) {
  out.push("");
  out.push("  git diff --check reports whitespace problems. Fix before committing.");
}

out.push("");
out.push("Recent commits:");
for (const commit of report.recent_commits) out.push(`  ${commit}`);

out.push("");
if (!ghAuthenticated) {
  out.push("GitHub: gh is not installed or not authenticated - PR and CI state unknown.");
} else {
  out.push(
    pullRequest
      ? `Pull request: #${pullRequest.number}${pullRequest.isDraft ? " (draft)" : ""} ${pullRequest.url}`
      : "Pull request: none open for this branch",
  );

  if (workflows.length === 0) {
    out.push("CI: no runs recorded for this branch");
  } else {
    out.push("CI (latest run per workflow):");
    for (const workflow of workflows) {
      const result = workflow.status === "completed" ? workflow.conclusion : workflow.status;
      out.push(
        `  ${String(result).padEnd(12)} ${workflow.name}  ${String(workflow.headSha).slice(0, 7)}`,
      );
    }
  }
}

out.push("");
out.push("These are mechanical facts only. This script cannot tell whether the work is");
out.push("finished. Write the semantic progress and the exact next actions into");
out.push("PROJECT_STATE.md yourself.");

process.stdout.write(`${out.join("\n")}\n`);
