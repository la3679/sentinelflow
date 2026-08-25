#!/usr/bin/env node
/**
 * SentinelFlow status line.
 *
 * Claude Code pipes a JSON session object on stdin and renders whatever this
 * prints. The build standards require the branch, a dirty indicator, and the
 * context-window percentages to be visible at all times, because the checkpoint
 * protocol is driven by that percentage.
 *
 * Written in JavaScript rather than shell for two reasons: the reference
 * machine is Windows and a bash status line is a second thing that can be
 * missing, and `jq` is not a prerequisite of this repository. Bun is, and it
 * parses JSON without help.
 *
 * Nothing here may throw. A status line that crashes leaves the operator with
 * no context reading at all, which is worse than a degraded one, so every
 * field is treated as optional and the whole render is wrapped.
 *
 * Debug with:
 *   echo '{"model":{"display_name":"Opus"},"context_window":{"used_percentage":81}}' \
 *     | bun .claude/statusline.mjs
 */

import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const ESC = "\u001b[";
const RESET = `${ESC}0m`;
const DIM = `${ESC}2m`;
const RED = `${ESC}31m`;
const GREEN = `${ESC}32m`;
const YELLOW = `${ESC}33m`;
const BLUE = `${ESC}34m`;
const MAGENTA = `${ESC}35m`;
const CYAN = `${ESC}36m`;

/** Read all of stdin. Returns "" rather than hanging if nothing arrives. */
async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  return Buffer.concat(chunks).toString("utf8");
}

/** Run git in `cwd`, returning trimmed stdout or null. Never throws. */
function git(cwd, args) {
  try {
    return execFileSync("git", args, {
      cwd,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
      timeout: 2000,
    }).trim();
  } catch {
    return null;
  }
}

/**
 * Colour by the checkpoint thresholds in CLAUDE.md, so the status line says
 * what the protocol requires rather than just reporting a number:
 *   under 70  work normally
 *   70-79     finish the current unit, do not start a large one
 *   80-84     mandatory checkpoint
 *   85+       emergency checkpoint
 */
function contextSegment(contextWindow) {
  const used = contextWindow?.used_percentage;
  const remaining = contextWindow?.remaining_percentage;

  // Null early in a session, and again after /compact until the next API call.
  if (typeof used !== "number" || Number.isNaN(used)) {
    return `${DIM}ctx --${RESET}`;
  }

  const usedInt = Math.round(used);
  const remainingInt =
    typeof remaining === "number" && !Number.isNaN(remaining)
      ? Math.round(remaining)
      : Math.max(0, 100 - usedInt);

  let colour = GREEN;
  let note = "";
  if (usedInt >= 85) {
    colour = RED;
    note = " EMERGENCY CHECKPOINT";
  } else if (usedInt >= 80) {
    colour = RED;
    note = " CHECKPOINT NOW";
  } else if (usedInt >= 70) {
    colour = YELLOW;
    note = " finish current unit";
  }

  const filled = Math.min(10, Math.max(0, Math.round(usedInt / 10)));
  const bar = "▓".repeat(filled) + "░".repeat(10 - filled);

  return `${colour}${bar} ${usedInt}% used / ${remainingInt}% left${note}${RESET}`;
}

function gitSegment(cwd) {
  if (!cwd) return null;

  const branch = git(cwd, ["branch", "--show-current"]);
  if (branch === null) return null;

  const name = branch === "" ? (git(cwd, ["rev-parse", "--short", "HEAD"]) ?? "detached") : branch;

  // --porcelain is the stable, parseable form; its emptiness is the whole test.
  const status = git(cwd, ["status", "--porcelain"]);
  const dirtyCount = status ? status.split("\n").filter(Boolean).length : 0;
  const state = dirtyCount > 0 ? `${YELLOW}${dirtyCount} dirty${RESET}` : `${GREEN}clean${RESET}`;

  // Ahead/behind against the upstream, when one exists.
  let tracking = "";
  const counts = git(cwd, ["rev-list", "--left-right", "--count", "@{upstream}...HEAD"]);
  if (counts) {
    const [behind, ahead] = counts.split(/\s+/).map((n) => Number.parseInt(n, 10) || 0);
    const parts = [];
    if (ahead > 0) parts.push(`↑${ahead}`);
    if (behind > 0) parts.push(`↓${behind}`);
    if (parts.length > 0) tracking = ` ${MAGENTA}${parts.join("")}${RESET}`;
  }

  return `${CYAN}${name}${RESET}${tracking} ${state}`;
}

/**
 * Record the latest context percentage in a git-ignored runtime file, so
 * scripts/claude/checkpoint.mjs can report it. Claude Code passes the
 * percentage to the status line and nowhere else, and a checkpoint that has to
 * guess at it is a checkpoint fired at the wrong time.
 *
 * Best-effort and completely silent: this runs on every status-line render, and
 * a failed write must never take the status line down with it.
 */
function recordContext(cwd, contextWindow) {
  const used = contextWindow?.used_percentage;
  if (typeof used !== "number" || Number.isNaN(used)) return;
  try {
    const dir = join(cwd, ".claude", "runtime");
    mkdirSync(dir, { recursive: true });
    writeFileSync(
      join(dir, "context.json"),
      JSON.stringify({
        used_percentage: Math.round(used),
        remaining_percentage:
          typeof contextWindow?.remaining_percentage === "number"
            ? Math.round(contextWindow.remaining_percentage)
            : null,
        context_window_size: contextWindow?.context_window_size ?? null,
        recorded_at_utc: new Date().toISOString(),
      }),
      "utf8",
    );
  } catch {
    // Ignored on purpose.
  }
}

async function main() {
  let session = {};
  try {
    const raw = await readStdin();
    if (raw.trim()) session = JSON.parse(raw);
  } catch {
    // Malformed or absent input: render what little is known rather than
    // failing. An empty status line hides the context percentage, and the
    // checkpoint protocol depends on seeing it.
  }

  const cwd = session.workspace?.current_dir ?? session.cwd ?? process.cwd();
  const model = session.model?.display_name ?? "claude";
  const sessionName = session.session_name;
  const dirLabel =
    String(cwd)
      .replace(/[\\/]+$/, "")
      .split(/[\\/]/)
      .pop() || cwd;

  const first = [
    `${BLUE}${model}${RESET}`,
    sessionName ? `${DIM}${sessionName}${RESET}` : null,
    `${DIM}${dirLabel}${RESET}`,
    gitSegment(cwd),
  ].filter(Boolean);

  // Rate-limit usage is a different thing from context usage and is shown
  // separately so the two are never confused. Absent for non-subscription
  // sessions, and independently absent per window.
  const fiveHour = session.rate_limits?.five_hour?.used_percentage;
  const limitSegment =
    typeof fiveHour === "number" ? `${DIM}5h limit ${Math.round(fiveHour)}%${RESET}` : null;

  recordContext(cwd, session.context_window);

  const second = [contextSegment(session.context_window), limitSegment].filter(Boolean);

  process.stdout.write(`${first.join(` ${DIM}|${RESET} `)}\n${second.join(`  ${DIM}|${RESET}  `)}`);
}

main().catch(() => {
  // Last resort. Print something rather than nothing.
  process.stdout.write("sentinelflow");
});
