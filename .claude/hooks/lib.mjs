/**
 * Shared helpers for SentinelFlow's Claude Code hooks.
 *
 * Rules every hook in this directory follows:
 *
 *   - Never throw. A hook that crashes is noise in someone's session; a hook
 *     that crashes on every turn is a reason to disable the whole set.
 *   - Never mutate the repository. No commits, no pushes, no staging, no
 *     deletions. Hooks gather facts and remind; the operator decides.
 *   - Never read or emit a secret. `.env` is never opened here.
 *   - Stay fast. Every git call is bounded by a timeout.
 *
 * See docs/development/CLAUDE_CODE_SETUP.md for how to debug or disable these.
 */

import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

/** Read the JSON object Claude Code writes to stdin. Returns {} on anything odd. */
export async function readHookInput() {
  try {
    const chunks = [];
    for await (const chunk of process.stdin) chunks.push(chunk);
    const raw = Buffer.concat(chunks).toString("utf8").trim();
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

/** Run git in `cwd`. Returns trimmed stdout, or null on any failure. */
export function git(cwd, args, timeout = 3000) {
  try {
    return execFileSync("git", args, {
      cwd,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
      timeout,
    }).trim();
  } catch {
    return null;
  }
}

/** The repository root containing `cwd`, or null if there is not one. */
export function repoRoot(cwd) {
  return git(cwd, ["rev-parse", "--show-toplevel"]);
}

/**
 * A snapshot of Git state. Read-only: nothing here writes to the repository or
 * contacts a remote, so it is safe to call from any hook.
 */
export function gitSnapshot(root) {
  const porcelain = git(root, ["status", "--porcelain"]) ?? "";
  const dirtyPaths = porcelain
    .split("\n")
    .filter(Boolean)
    .map((line) => line.slice(3).trim());

  return {
    branch: git(root, ["branch", "--show-current"]) || "(detached)",
    head: git(root, ["rev-parse", "HEAD"]),
    headShort: git(root, ["rev-parse", "--short", "HEAD"]),
    upstream: git(root, ["rev-parse", "--abbrev-ref", "@{upstream}"]),
    upstreamHead: git(root, ["rev-parse", "@{upstream}"]),
    dirtyPaths,
    recentCommits: (git(root, ["log", "--oneline", "-5"]) ?? "").split("\n").filter(Boolean),
  };
}

/**
 * Paths that count as "the project changed" for the purpose of deciding whether
 * PROJECT_STATE.md has gone stale. Build output, local environment files and
 * the state document itself are excluded - a dirty coverage report is not a
 * reason to ask anyone to checkpoint.
 */
export function significantChanges(dirtyPaths) {
  const ignored = [
    /^\.env/,
    /(^|\/)node_modules\//,
    /(^|\/)target\//,
    /(^|\/)dist\//,
    /(^|\/)coverage\//,
    /(^|\/)test-results\//,
    /(^|\/)playwright-report\//,
    /(^|\/)\.venv\//,
    /(^|\/)__pycache__\//,
    /(^|\/)\.claude\/runtime\//,
    /^PROJECT_STATE\.md$/,
    /^docs\/planning\/SESSION_LOG\.md$/,
  ];
  return dirtyPaths.filter((p) => !ignored.some((re) => re.test(p)));
}

/** The git-ignored directory hooks may write to. Created on demand. */
export function runtimeDir(root) {
  const dir = join(root, ".claude", "runtime");
  try {
    mkdirSync(dir, { recursive: true });
    return dir;
  } catch {
    return null;
  }
}

/** Write a git-ignored runtime file. Silent on failure - never fatal. */
export function writeRuntimeFile(root, name, contents) {
  const dir = runtimeDir(root);
  if (!dir) return null;
  const path = join(dir, name);
  try {
    writeFileSync(path, contents, "utf8");
    return path;
  } catch {
    return null;
  }
}

/** Emit `hookSpecificOutput.additionalContext` and exit cleanly. */
export function emitContext(hookEventName, additionalContext) {
  process.stdout.write(
    JSON.stringify({ hookSpecificOutput: { hookEventName, additionalContext } }),
  );
  process.exit(0);
}

/** Emit nothing and exit cleanly. The normal path for a side-effect-only hook. */
export function emitNothing() {
  process.exit(0);
}
