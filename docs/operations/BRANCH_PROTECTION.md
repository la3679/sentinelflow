# Branch protection

`main` is protected by a GitHub **ruleset** named `main protection`, enabled 2026-08-25 at the end
of Phase 1 — after the CI check names existed, and after the initial Lovable phase
([ADR-0001](../adr/0001-lovable-first-repository-creation.md)).

## What is enforced

| Rule                     | Effect                                              |
| ------------------------ | --------------------------------------------------- |
| `pull_request`           | `main` accepts merges through a pull request only   |
| `required_status_checks` | Nine checks must pass before a merge is allowed     |
| `non_fast_forward`       | Force pushes to `main` are rejected                 |
| `deletion`               | `main` cannot be deleted                            |
| Conversation resolution  | Every review thread must be resolved before merging |

**Bypass actors: none.** The rules apply to the repository owner exactly as they apply to anyone
else. A rule the owner can walk past is documentation, not protection.

## Required checks

| Check                                           | Workflow            |
| ----------------------------------------------- | ------------------- |
| `Formatting`                                    | `ci-repo.yml`       |
| `Lint, typecheck, unit tests, build`            | `ci-web.yml`        |
| `Browser, accessibility, and responsive checks` | `ci-web.yml`        |
| `Build, test, and formatting gate`              | `ci-api.yml`        |
| `Lint, type-check, and test`                    | `ci-scoring.yml`    |
| `Build and scan api`                            | `ci-containers.yml` |
| `Build and scan scoring`                        | `ci-containers.yml` |
| `Build and scan web`                            | `ci-containers.yml` |
| `Secret scan`                                   | `security-scan.yml` |

### Why `Dependency review` is not required

It runs on `pull_request` events only, and its job is additionally gated on the repository being
public — GitHub provides the dependency graph free on public repositories and behind paid Advanced
Security on private ones.

A required check that does not report **blocks a pull request forever**. Requiring this one would
mean that making the repository private, even briefly, would deadlock every open pull request. It
still runs on every pull request and still fails visibly; it is simply not a merge gate.

This is also why **no workflow is path-filtered** — a workflow skipped by an `on: paths` filter
produces no check run at all, with the same deadlocking effect. See
[ADR-0002](../adr/0002-monorepo-and-service-boundaries.md).

### Why zero required approvals

There is one maintainer. Requiring an approving review would make every pull request
unmergeable — the author cannot approve their own. Requiring a review that cannot happen is worse
than not requiring one, because it produces a workaround culture of bypassing the rule.

What is enforced instead is that the change went through a pull request, that CI passed, and that
every review conversation was resolved. `CODEOWNERS` still requests a review on every change.

Revisit if the project gains a second maintainer.

## Why strict "up to date before merging" is off

`strict_required_status_checks_policy` is `false`, so a branch does not have to be rebased onto the
latest `main` before merging.

With one maintainer and a serial workflow, `main` rarely moves under an open pull request. Turning
it on would force a rebase-and-rerun cycle on every merge for a race that does not occur here.
Turn it on if concurrent pull requests become normal.

## Verifying the rules

```bash
gh api repos/la3679/sentinelflow/rules/branches/main --jq '[.[] | .type] | unique'
gh api repos/la3679/sentinelflow/rulesets --jq '.[] | {id, name, enforcement}'
```

**`git push --dry-run` does not test this.** A dry-run push reports success against a protected
branch because it never reaches the ruleset evaluation. The API above is the authoritative check;
trusting a dry run would mean believing protection is in place when it is not.

## Dependabot and the Bun lockfile

Dependabot edits `apps/web/package.json` but does not regenerate `bun.lock`, and specifically does
not do so for a Bun workspace — [dependabot-core#11602](https://github.com/dependabot/dependabot-core/issues/11602)
and [dependabot-core#14223](https://github.com/dependabot/dependabot-core/issues/14223). Every npm
pull request it opens therefore fails at `bun install --frozen-lockfile`.

`.github/workflows/dependabot-bun-lockfile.yml` regenerates the lockfile and pushes it onto the
Dependabot branch. It is the only workflow in this repository with `contents: write`, it is gated
on `github.actor == 'dependabot[bot]'`, and it touches nothing but `bun.lock`.

The alternative — dropping `--frozen-lockfile` from CI — was rejected. A frozen install is what
makes the build reproducible, and weakening a real guarantee to accommodate a tooling gap is the
wrong trade.

**One manual step remains.** A push made with the default `GITHUB_TOKEN` cannot start further
workflow runs, so CI does not re-run by itself once the lockfile lands. The pull request is then
correct but has no checks, and the ruleset leaves it blocked. Re-trigger it with:

```bash
gh pr close <number> && gh pr reopen <number>
```

Removing that step would need a personal access token or a GitHub App token — a standing
credential with write access to this repository. That is a larger and more permanent security
surface than one command on a weekly bot pull request is worth.

## Interaction with Lovable

Lovable responds to a rejected protected-branch push by silently diverting the change to a backup
branch rather than failing outright. That is why this ruleset was enabled only after the initial
Lovable phase completed.

Since Phase 1 the console lives at `apps/web/` and Lovable no longer regenerates this project, so
the interaction no longer arises in practice. See
[`LOVABLE_GITHUB_WORKFLOW.md`](LOVABLE_GITHUB_WORKFLOW.md).

## Changing the ruleset

```bash
gh api repos/la3679/sentinelflow/rulesets/21493410 --jq '.'   # inspect
gh api -X PUT repos/la3679/sentinelflow/rulesets/21493410 --input ruleset.json
```

Adding a required check means adding its **job name**, exactly as GitHub reports it — the check
name is the job's `name:`, not the workflow's. Verify with:

```bash
gh api repos/la3679/sentinelflow/commits/main/check-runs --jq '.check_runs[].name' | sort -u
```

Never disable a required check to make a merge possible. Fix the check, or record why the rule was
wrong in an ADR.
