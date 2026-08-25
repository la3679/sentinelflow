## Summary

<!-- What changes and why. One or two sentences a reviewer can act on. -->

## Motivation

<!-- The problem this solves. Link the issue or ADR if there is one. -->

## Changes

<!-- The notable changes, grouped by component. -->

- `apps/api`:
- `apps/scoring`:
- `apps/web`:
- infrastructure / docs:

## Test evidence

<!-- Commands actually run, and their actual results. Not "tests pass" - the
     command and the number. If something was not run, say so. -->

| Command | Result |
| ------- | ------ |
|         |        |

## Security impact

<!-- New inputs, new external calls, new dependencies, changes to authorization,
     changes to what is logged or exposed. "None" is a valid answer when true. -->

## Performance impact

<!-- Measured, or "not measured" - never estimated and presented as measured. -->

## Migration notes

<!-- Database migrations, event-schema changes, breaking API changes, or
     configuration a deployer must set. "None" if none. -->

## Documentation

<!-- Which documents changed, or why none needed to. -->

## Checklist

- [ ] The change is scoped to one coherent thing
- [ ] Tests cover the new behaviour, and they were run
- [ ] Documentation and diagrams match the code
- [ ] No secrets, credentials, or real personal data
- [ ] No invented figures - every number quoted came from a run
- [ ] Contracts, ADRs, and `PROJECT_STATE.md` updated where affected
- [ ] Self-reviewed the full diff
