#!/usr/bin/env node
/**
 * Documentation checks that Prettier cannot do.
 *
 * Documentation drifting from the code is treated as a defect in this
 * repository, and the most common form of drift is a link to a file that was
 * renamed or never created. This walks every tracked Markdown file and resolves
 * every relative link.
 *
 * It also flags the placeholder text that must never reach a release: TODO
 * markers, "coming soon", and YOUR_ORG-style templates left in a document.
 *
 *   bun scripts/dev/check-docs.mjs
 *
 * Wired into `make docs-check`.
 */

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));

function git(args, cwd) {
  return execFileSync("git", args, {
    cwd,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
  }).trim();
}

const ROOT = git(["rev-parse", "--show-toplevel"], HERE);

// Tracked files only, listed from the repository root: `git ls-files` is scoped
// to the current directory, so running it from scripts/dev would silently find
// nothing and report success.
const markdownFiles = git(["ls-files", "*.md"], ROOT).split("\n").filter(Boolean);

const LINK = /\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;

// Deliberately narrow. "TODO" inside a fenced code block that is illustrating a
// TODO is fine; these are the forms that mean the document is unfinished.
const PLACEHOLDERS = [
  { pattern: /\bTODO\b(?!\s*\()/, label: "TODO marker" },
  { pattern: /\bFIXME\b/, label: "FIXME marker" },
  { pattern: /coming soon/i, label: '"coming soon"' },
  { pattern: /YOUR_ORG|YOUR_USERNAME|<your-[a-z-]+>/i, label: "unfilled template placeholder" },
  { pattern: /example\.com/i, label: "example.com placeholder URL" },
];

let linksChecked = 0;
const brokenLinks = [];
const placeholders = [];

for (const file of markdownFiles) {
  const absolute = join(ROOT, file);
  if (!existsSync(absolute)) continue;

  const contents = readFileSync(absolute, "utf8");

  // Strip fenced code blocks before scanning for placeholders, so a code
  // sample that legitimately shows a TODO is not a finding.
  const prose = contents.replace(/```[\s\S]*?```/g, "");

  for (const { pattern, label } of PLACEHOLDERS) {
    const match = prose.match(pattern);
    if (match) placeholders.push(`${file}: ${label} - "${match[0]}"`);
  }

  for (const match of contents.matchAll(LINK)) {
    const target = match[1];
    if (/^(https?:|mailto:|#)/.test(target)) continue;

    const pathPart = target.split("#")[0];
    if (!pathPart) continue;

    linksChecked += 1;
    if (!existsSync(resolve(dirname(absolute), pathPart))) {
      brokenLinks.push(`${file} -> ${target}`);
    }
  }
}

const problems = brokenLinks.length + placeholders.length;

process.stdout.write(
  `Checked ${linksChecked} relative link(s) across ${markdownFiles.length} Markdown file(s).\n`,
);

if (brokenLinks.length > 0) {
  process.stdout.write(`\n${brokenLinks.length} broken link(s):\n`);
  for (const item of brokenLinks) process.stdout.write(`  ${item}\n`);
}

if (placeholders.length > 0) {
  process.stdout.write(`\n${placeholders.length} placeholder(s) left in documentation:\n`);
  for (const item of placeholders) process.stdout.write(`  ${item}\n`);
}

if (problems > 0) {
  process.stdout.write("\nDocumentation check failed.\n");
  process.exit(1);
}

process.stdout.write("No broken links and no placeholders.\n");
