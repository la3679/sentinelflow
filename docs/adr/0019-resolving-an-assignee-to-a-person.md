# ADR-0019 — Resolving an assignee to a person: an operator directory, and identity on the token response

- **Status:** Accepted
- **Date:** 2026-09-01
- **Related:** [ADR-0012](0012-operator-authentication.md),
  [ADR-0014](0014-where-the-console-s-remaining-screens-get-their-data.md),
  [ADR-0002](0002-monorepo-and-service-boundaries.md),
  the v1 release requirement that an alert can be given to a named analyst

## Context

The console could release an alert and could not give one to anybody.

`alerts.assignee_id` is a `uuid`. The API published it and published nothing that
turned it into a name, so the queue's assignee column said **"Assigned"** rather than who, and the
investigation screen offered one control — release — beside a sentence explaining why the other one
did not exist. That was honest, and it was a hole in the core workflow of a fraud-operations
console: the whole point of a queue is that work belongs to somebody.

**Three things were missing, and only one of them was obvious.**

1. Nothing resolved an identifier to a person.
2. Nothing listed the people an alert could be given to, so a picker had nothing to populate from.
3. **The operator did not know their own identifier.** The login response carried the roles held at
   login and not the subject of the token it came with, so not even "assign this to me" was
   buildable — which is why the screen said so rather than drawing a button that would answer `422`.

The assignment endpoint itself was already complete and already correct. `PUT /alerts/{id}/assignment`
validated that the assignee exists, is active, and holds a role that can work an alert; refused an
`AUDITOR`; took an `expectedVersion` and answered `409` on a stale one; and treated a repeated
assignment as a no-op. **None of that changes here.** What was missing was everything a client needed
in order to use it.

That requirement fixes what "done" means, and every clause of it is binding.

## Decision

### 1. `GET /api/v1/operators` — who may be given an alert

A paged, bounded list of **active** operators holding a role that can work an alert.

**Two exclusions, for two different reasons**, and the difference matters:

- **A disabled account** is excluded because giving work to somebody who cannot sign in produces a
  queue entry nobody will ever clear.
- **An `AUDITOR`** is excluded because [ADR-0012 §4](0012-operator-authentication.md) makes the role
  read-only, so assigning one would create work they are not permitted to do.

The `system` principal is excluded by both, and could not hold a token in any case.

**This is the same rule the assignment endpoint enforces, and that is the point.** A picker that
offered somebody the server would refuse is a dead control, which
[`ENGINEERING_STANDARDS.md`](../development/ENGINEERING_STANDARDS.md)
forbids. It is **an affordance and never an authorization**: the server checks again on the way in,
because a client is free to send an identifier this endpoint never gave it, and
`OperatorDirectoryIT` proves it by assigning an auditor the directory never listed and asserting the
refusal.

Readable by every authenticated role, **including `AUDITOR`**. An auditor already sees the assignee
of every alert; withholding a list of names from them would protect nothing.

Paged and bounded at the same cap as the alert queue. Four operators exist today, which is exactly
the situation in which somebody argues a bound is unnecessary and an endpoint quietly becomes
unbounded before anybody adds a fifth.

### 2. `Alert.assignee` — who holds this one

The alert carries the resolved person beside the identifier, and **`assigneeId` stays**: it is the
value the assignment endpoint takes back, and a client already storing it should not have to change.

**Resolved server-side so that every client agrees.** A queue row has to render a person. If only
the identifier were published, every client would need the operator directory loaded before it could
draw one row, and each would decide differently what to show for an identifier it could not resolve.

**This is deliberately not the same question the directory answers.** `GET /operators` excludes
anybody who may no longer be given an alert. This must still name an operator whose account has
since been disabled — an alert assigned last week to somebody who has left is **not** unassigned, and
an audit trail that quietly forgot them would be worse than one that names them. There is a test for
exactly that: the directory stops offering the operator, and the alert keeps naming them.

Null exactly when `assigneeId` is null, with one representable exception: an identifier that resolves
to no row publishes the id with a null assignee. That state is not reachable through the schema
today, and the honest answer to "who is this" is nothing rather than a placeholder.

### 3. `TokenResponse.operatorId` and `displayName` — who I am

The identifier is already inside the token as its subject. It is published beside the token for the
same reason the expiry and the roles are: **a client should not have to decode a structure this
service is free to change** in order to learn who it just signed in as.

Its absence was a missing feature rather than a small omission — it is the whole reason "assign this
to me" did not exist.

The display name rides along so a screen can say who is signed in without a second request for a row
it already implicitly has.

### 4. Neither read is an N+1

A page of operators costs **two** queries regardless of size — the page, then every role held by the
operators on it. Not one query with a join, because a join to a to-many multiplies the rows and makes
the page size mean something other than "this many operators".

A page of alerts costs **one** extra query, or **none** when nothing on it is assigned — which is the
ordinary case on a healthy queue. The tuned queue query is untouched.

### 5. What this does not do

- **No new roles, no new permissions, no change to who may assign.** Every authorization decision is
  where it was.
- **No operator management.** Nothing here creates, disables or edits an operator. The directory is
  read-only, and the four demo operators come from the seed.
- **No credential, email address or last-login time on the wire.** None is needed to put a name in a
  picker, and each would be a new thing to protect.
- **No `status` field on `Operator`.** Every operator the endpoint returns is active — a disabled one
  is filtered out by the query rather than returned with a flag for a client to check and forget to
  check. A field whose value is always the same invites a filter that will silently never fire.

## Consequences

- **The core workflow closes.** An alert can be given to a named analyst, the queue says who has it,
  and an analyst can take one themselves.
- **The console's remaining honest omission is gone**, and with it the copy on the investigation
  screen explaining the omission.
- **`Alert` grew a field**, additively. A client reading only `assigneeId` keeps working.
- **A second read exists that a screen depends on**, so the picker owes the four states every data
  view owes — including empty, which is real: a deployment whose operators are all auditors has
  nobody to assign to.
- **The token response now identifies its bearer to the client.** It always did to the server; this
  publishes what the subject claim already said, and adds no capability the token did not carry.

## Revisit if

- **Operators stop coming from the seed** and acquire a lifecycle — invitation, disablement,
  role changes. A directory is read-only today because there is nothing to manage.
- **The number of operators outgrows a picker.** Two hundred names in a select is a search box, not a
  list, and the endpoint is already paged for it.
- **Assignment acquires rules beyond "can work an alert"** — a team, a queue, a shift, a workload cap.
  Each would make "who may be given this alert" depend on the alert, which this endpoint deliberately
  does not.
