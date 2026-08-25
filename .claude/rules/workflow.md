# Workflow rules

Binding for every session in this repository, regardless of which component is
being changed.

## The working-directory invariant

All implementation happens inside the clone whose `origin` is
`https://github.com/la3679/sentinelflow.git`. Before editing anything, confirm
it:

```bash
git rev-parse --show-toplevel
git remote -v
```

Never initialize a second repository inside this one. Never create a parallel
local project and try to replace this history later. If the invariant fails,
stop and say so rather than working in a temporary folder.

The `SessionStart` hook checks this automatically and says so loudly when the
origin is wrong. It is a safety net, not a substitute for looking.

## Commits

One understandable change per commit, with its tests. Conventional Commits with
a consistent scope. Explain _why_ in the body when the change is not
self-evident.

A commit must: build or be an intentional intermediate on a short-lived branch;
include the tests for the behaviour it changes; include the documentation for
any contract or workflow it changes; avoid unrelated formatting churn; pass
`git diff --check`; and exclude secrets, generated bulk data, IDE files, logs
and caches.

Do not implement a whole phase and drop it in one commit. Do not split
inseparable code to inflate a count either.

## Pushing

Push after every two to four commits, at every phase boundary, before invoking
Lovable, before compaction, before ending a session, and whenever context usage
reaches a checkpoint threshold.

**Never claim "pushed" until `git rev-parse HEAD` and
`git rev-parse origin/<branch>` have been compared and match.** A push that
printed no error is not evidence.

## Branches and pull requests

Short-lived branches: `feat/kafka-outbox`, `fix/csv-formula-injection`,
`docs/architecture-diagrams`, `chore/phase-N-...`.

Never force-push a shared branch. Never rewrite published history — Lovable
syncs this repository, and a rewrite corrupts its side too. On an unshared
branch, `--force-with-lease` only, after verifying the target and saying why.

Open a pull request for each substantial feature or phase, fill in the template
honestly, self-review the full diff, and merge only when the required checks
pass. Prefer a merge commit: the meaningful history is the point.

## Evidence

**No invented numbers.** Coverage, latency, throughput, accuracy, false-positive
rates, test counts, and image sizes are only ever reported from a run that
actually happened, with the command and the date recorded.

If something was not run, say it was not run. "Tests pass" without a count is
not evidence; neither is a rendering preview.

## Definition of done

Acceptance criteria met · behaviour reviewed at the domain, API and event level ·
validation and authorization present · failure modes handled · unit tests pass ·
integration, contract and end-to-end tests added where relevant · observability
added where operationally meaningful · security and privacy impact considered ·
documentation and diagrams updated · no placeholder or dead code · formatter,
linter and type checks pass · commit atomic and pushed · PR and check state
recorded · `PROJECT_STATE.md` updated.

## Never

- Commit `.env`, a token, a private key, or generated bulk data
- Disable a test, lower a threshold, or suppress a security finding to go green
- Use a real or realistic personal identifier anywhere, including in a test
- Copy GPL-licensed source into this Apache-2.0 repository
- Fabricate a commit date, a contributor, or a test result
- Add a technology without a demonstrated need and a recorded decision
