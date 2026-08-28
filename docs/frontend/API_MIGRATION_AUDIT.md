# API migration audit — what the console asks for, and what the API answers

**Audited:** 2026-08-28 · **Auditor:** Claude · **Opens:** Phase 6 · **Status:** closed — all four
pieces done

> **Closed on 2026-08-28.** All four pieces landed the same day: the transport and authentication,
> the types and mapping with the three transaction read endpoints this audit had wrongly recorded as
> existing, and the four invented endpoints — decided in
> [ADR-0014](../adr/0014-where-the-console-s-remaining-screens-get-their-data.md) and built.
> **`src/mocks/` is deleted**; every screen reads the API. One decision remains open and it is not
> the console's: how an assignee's identifier resolves to a person. Each section below still
> describes what was found; the plan at the end records what was done about it.

## Correction: three of the endpoints in the table below did not exist

**This audit says it checked the handlers. On the transaction endpoints it checked the contract.**
`contracts/openapi/` has described `GET /transactions`, `GET /transactions/{id}` and
`GET /transactions/{id}/assessment` since Phase 3; `TransactionController` implemented only the
`POST`. The verdict table's "Maps, with field renames" was true of the document and false of the
code, and the count of endpoints with no server counterpart was four when it was seven.

Found by writing the client the audit called for, which is the honest way it was ever going to be
found: nothing had called those paths, so nothing had discovered they answered 404. The three are
built and merged as [#62](https://github.com/la3679/sentinelflow/pull/62) with 16 integration
tests, so the table below is now accurate — but the lesson is the general one, and it is the same
shape as the reference collision and the seed's idempotency guard: **a check that reads one of two
documents and reports on both is a check that has not been made.**

Phase 6's first deliverable is "typed RTK Query API layer replacing the Lovable mock fixtures".
[`AGENTS.md`](../../AGENTS.md) describes that migration as "limited to replacing `mockBaseQuery`
with `fetchBaseQuery`". **It is not**, and this audit is the evidence, endpoint by endpoint, before
a line of client code is written against a shape that does not exist.

Every claim below was checked against
[`contracts/openapi/sentinelflow-api.yaml`](../../contracts/openapi/sentinelflow-api.yaml) — the
authoritative contract per [`CLAUDE.md`](../../CLAUDE.md) — and against the handlers themselves,
not against either document's description of the other.

## Verdict

| Console endpoint              | Real endpoint                           | Verdict                         |
| ----------------------------- | --------------------------------------- | ------------------------------- |
| `GET /overview`               | —                                       | **No counterpart**              |
| `GET /reports`                | `GET /reports/alert-summary`            | **Different shape**             |
| `GET /model-policy`           | `GET /models/active`                    | **Different shape**             |
| `GET /health`                 | `GET /actuator/health` (different base) | **Different shape and base**    |
| `GET /transactions`           | `GET /transactions`                     | Maps, with field renames        |
| `GET /transactions/{id}`      | `GET /transactions/{id}`                | **Different shape**             |
| `GET /alerts`                 | `GET /alerts`                           | Maps, with field renames        |
| `GET /alerts/{id}`            | `GET /alerts/{id}`                      | **Different shape**             |
| `PATCH /alerts/{id}/assignee` | `PUT /alerts/{id}/assignment`           | **Different verb, path, body**  |
| `PATCH /alerts/{id}/status`   | `POST /alerts/{id}/transition`          | **Different verb, path, body**  |
| `POST /alerts/{id}/notes`     | `POST /alerts/{id}/notes`               | **Different body and response** |
| —                             | `POST /auth/login`                      | **Not called at all**           |
| —                             | `GET /alerts/{id}/history`              | **Not called at all**           |
| —                             | `PUT /alerts/{id}/feedback`             | **Not called at all**           |
| —                             | `GET /transactions/{id}/assessment`     | **Not called at all**           |
| —                             | `GET /reports/alerts.csv`               | **Not called at all**           |

Of the console's eleven endpoints, two reach a real endpoint at the same verb and path and still
need every field renamed; the other nine need more. Five server endpoints have no client at all.

**The table above is what was found, and is left as found.** Where each stands after pieces 1 and 2:

| Now                                        | Which                                                                                                                                                              |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Reads the API**                          | login, the alert queue, one alert, its history, its notes, its transition, its assignment, the transaction list, one transaction, its assessment                   |
| **Composed or built, and reading the API** | the overview (composed from the alert summary and the queue), reports, model and policy, system health — piece 4                                                   |
| **Deleted rather than migrated**           | the queue's free-text search and risk-band filter, the feed's authorisation-status filter, `ALLOWED_TRANSITIONS`, the client-supplied `actor`, the assignee picker |
| **A server endpoint with still no client** | `PUT /alerts/{id}/feedback` alone. The CSV export gained one: the reports screen downloads the window it counts                                                    |

## The finding that matters most: the console renders controls the server refuses

`src/domain/types.ts` carries `ALLOWED_TRANSITIONS`, a second copy of the alert state machine.
[`AlertTransitions.java`](../../apps/api/src/main/java/io/github/la3679/sentinelflow/api/alert/AlertTransitions.java)
is the first. **They disagree, in both directions:**

| From                       | The console offers                                              | The server allows                                                                        |
| -------------------------- | --------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `NEW`                      | `IN_REVIEW`, **`DISMISSED_FALSE_POSITIVE`**                     | `IN_REVIEW`, **`CLOSED`**                                                                |
| `IN_REVIEW`                | `ESCALATED`, `CONFIRMED_SUSPICIOUS`, `DISMISSED_FALSE_POSITIVE` | **`NEW`**, `ESCALATED`, `CONFIRMED_SUSPICIOUS`, `DISMISSED_FALSE_POSITIVE`, **`CLOSED`** |
| `ESCALATED`                | `CONFIRMED_SUSPICIOUS`, `DISMISSED_FALSE_POSITIVE`              | **`IN_REVIEW`**, `CONFIRMED_SUSPICIOUS`, `DISMISSED_FALSE_POSITIVE`, **`CLOSED`**        |
| `CONFIRMED_SUSPICIOUS`     | **`CLOSED`**                                                    | nothing — terminal                                                                       |
| `DISMISSED_FALSE_POSITIVE` | **`CLOSED`**                                                    | nothing — terminal                                                                       |

Read as behaviour: the console today offers an analyst two buttons the server answers `409` to
(`NEW → DISMISSED_FALSE_POSITIVE`, because a disposition is a claim that somebody looked; and
closing an already-dispositioned alert, because those states are terminal), and hides four moves
that are legal (`NEW → CLOSED`, `IN_REVIEW → NEW`, `IN_REVIEW → CLOSED`, `ESCALATED → IN_REVIEW`).

**Phase 6's gate is "no dead controls".** A button that always fails is the definition of one, and
this is not a bug to fix in the map — it is the map. `.claude/rules/frontend.md` already says it:
"No business logic in a component. Risk rules, thresholds, and state transitions belong to the API."

**The fix is to delete `ALLOWED_TRANSITIONS`, not to correct it.** A corrected copy is still a copy,
and the next change to the server's state machine puts it back out of step silently. The server
already supplies what the console needs: an `IllegalAlertTransitionException` answers `409` with a
`legalTargets` property naming what the caller may do instead. What is missing is the same list on
the _happy_ path — a client should not have to attempt a move to learn it was available.

**This needs a small API addition**, recorded here rather than assumed: `GET /alerts/{id}` should
carry the alert's legal targets, computed from the same `AlertTransitions.LEGAL` map the refusal
uses. One source, read two ways.

## Enumerations that do not line up

**`AlertPriority` is incompatible.** The console has `P1 | P2 | P3 | P4`; the API has
`LOW | MEDIUM | HIGH | URGENT`. Not a rename — the console's labels imply an ordering convention
(P1 highest) that the API's do not share, and `priorityByBand` on the server maps a risk band to
the API's four. The console's four must go.

**`TransactionStatus` is a different concept.** The console has
`AUTHORIZED | DECLINED | PENDING | REVERSED`, which describes what a payment switch decided. The API
has `processingStatus: PENDING | ASSESSED | FAILED`, which describes how far _this_ system has got
with it. SentinelFlow never authorizes or declines anything — it scores. The console's enum
describes a product this is not, and showing it would be the kind of claim
[`CLAUDE.md`](../../CLAUDE.md) forbids.

**`Role` gains a fourth value.** `AlertAction.actorRole` includes `SYSTEM`, because the alert-raising
path is attributed to the system principal. A console that types roles as the three human ones
cannot render its own audit trail.

## Identifiers: the console shows what it was given, and would show a UUID

The mock returns human-readable handles where the API returns UUIDs.

| Console field       | API field                                              |
| ------------------- | ------------------------------------------------------ |
| `alertId`           | `alertId` (uuid) **and** `alertReference` (`ALT-0001`) |
| `assignee` (a name) | `assigneeId` (uuid)                                    |
| `accountId`         | `accountReference` (`ACC-000123`)                      |
| `merchantId`        | `merchantReference` (`MER-0042`)                       |
| `transactionId`     | `transactionId` (uuid) **and** `transactionReference`  |

Per [ADR-0007](../adr/0007-money-identifiers-and-schema-migrations.md), the reference is the handle
a person uses and the UUID is the key. **A queue row showing a UUID is unreadable**, so every screen
must display the reference and route on the identifier. That is a deliberate two-field design the
console currently collapses into one.

**`assigneeId` is the sharpest case.** The API returns a UUID and there is no endpoint that resolves
one to a display name. An assignee column can currently render nothing a person recognises. Either
the API grows a name on the alert or a small user-lookup endpoint; that decision is Phase 6's, and it
is a real one rather than a mapping detail.

## The client names its own actor, which the API will not accept

Three mock mutations take an `actor` field in the request body:

```text
PATCH /alerts/{id}/assignee   { assignee, actor }
PATCH /alerts/{id}/status     { status, actor }
POST  /alerts/{id}/notes      { body, actor }
```

The API takes none. The actor is the `sub` claim of the bearer token (ADR-0012), which is what makes
`alert_actions.actor_role` an honest record of the capacity somebody acted in. **A client that names
its own actor is a forgeable audit trail**, and the field must not survive the migration even as an
ignored one.

## Optimistic concurrency has no client at all

`PUT /alerts/{id}/assignment` and `POST /alerts/{id}/transition` both require `expectedVersion`, and
a request without one is refused before anything is read — optional optimistic concurrency is not
optimistic concurrency. The console has no concept of a version: `Alert.version` is absent from
`domain/types.ts`, no mutation sends one, and nothing handles the `409` that carries
`currentVersion`.

This is the largest piece of genuinely new client work in the migration, and it is not plumbing: a
`409` needs a real user experience — re-read the alert, show what changed, and ask again — rather
than a toast that says "conflict".

## The four endpoints the console invents

**`GET /overview`** returns throughput series, risk-band counts, alert-status counts, a latency
summary, consumer-group lag and DLQ depth. Nothing serves it. Every part exists somewhere — the
counts in `GET /reports/alert-summary`, the lag and latency in Prometheus — but no HTTP endpoint
composes them. **The decision is whether the API grows an overview aggregate or the console composes
one**, and it should be made explicitly: an aggregate endpoint is a second place risk-band counting
logic lives, and a client-side composition means an overview screen that fires five requests and can
be half-loaded.

**`GET /health`** wants component states and pipeline lag. `GET /actuator/health` is on a different
base path, is shaped by Spring Boot rather than by this contract, and its detailed output is closed
to anonymous callers. A system-health screen needs a decision about what it may show and to whom.

**`GET /reports`** wants a daily alert trend, risk-band counts and feedback outcomes.
`GET /reports/alert-summary` gives band and status counts over one window and no trend; nothing
serves per-day buckets or feedback aggregates.

**`GET /model-policy`** maps _nearly_ onto `GET /models/active`, which returns `modelVersion`,
`featureVersion`, `policyVersion`, `status`, `trainedAt`, `promotedAt`, `metrics` and `limitations`.
The console additionally wants `thresholds` — the band boundaries — which the API does not publish.

## Authentication does not exist on the client

`demoOperatorSlice` is explicit that it is not authentication: no token, no credential, nothing
persisted, every route reachable directly. That was correct for Phase 0 and is what
[`FOUNDATION_AUDIT.md`](FOUNDATION_AUDIT.md) checked it against.

Phase 6 needs the real flow: `POST /auth/login` for a short-lived token, an `Authorization: Bearer`
header on every request, roles read from the token rather than chosen from a menu, and an expiry
that arrives as a `401` mid-session and has to be handled. There is no refresh token by design, so
"your session ended, sign in again" is a state every screen must survive.

**The role selector must go.** Choosing your own role is the interface equivalent of naming your own
actor.

## What this audit changes about the plan

The migration is four pieces of work, not one, and only the first is what `AGENTS.md` describes:

1. **Transport and authentication** — `fetchBaseQuery`, the login flow, the bearer header, `401`
   handling. Prerequisite for everything else. **Done, 2026-08-28.** `src/api/transport.ts` carries
   the bearer token and maps RFC 9457 to one error shape; `sessionSlice` holds the token in memory;
   the role selector and `demoOperatorSlice` are gone. Two things the work added that this audit did
   not anticipate: the API had no CORS configuration at all, which
   [ADR-0013](../adr/0013-console-to-api-cross-origin-access.md) now decides, and `TokenResponse`
   grew `roles` so the console need not decode the token to learn them.
2. **Types and mapping** — `domain/types.ts` rewritten against the contract: real enums, the
   reference/identifier pair, `version`, `SYSTEM` as a role, and the removal of
   `ALLOWED_TRANSITIONS` and the client-supplied `actor`. **Done, 2026-08-28.** The alert queue,
   the alert detail, the transaction feed and the transaction detail all read the API; the `409` is
   answered by re-reading the alert, saying what it is now, and offering the move again. Three
   things the work found that this audit had not:

   - **The three transaction read endpoints did not exist.** See the correction at the top.
   - **The console cannot assign an alert to anybody**, which is sharper than "an assignee renders
     as a UUID". Assignment takes an identifier; nothing resolves a name to one, and the login
     response carries the operator's roles but not their own identifier — so not even "assign to
     me" can be built. Release, which sends `null`, is the only assignment this console can make,
     and it is the only one offered.
   - **Nothing goes from a transaction to its alert.** `GET /alerts` filters on status, priority
     and assignee, and there is no lookup by transaction. The transaction page says so rather than
     offering a link it would have to guess at. The route that exists is alert to transaction.

   Two smaller decisions, recorded because each removed a control rather than adding one: the
   queue's free-text search and risk-band filter are gone, because `GET /alerts` has neither and a
   filter that quietly matched everything is a dead control; and the transaction feed's
   authorisation-status filter is gone with the enum behind it, because this system scores and
   never authorises. The feed is now polling rather than a window advancing over a fixture, which
   is what "live" can honestly mean against a real API.

3. **Two small API additions**, each needing its own decision: legal targets on `GET /alerts/{id}`
   — **done**, merged as [#57](https://github.com/la3679/sentinelflow/pull/57) — and whatever
   resolves an `assigneeId` to a name, which is still undecided.
4. **The four invented endpoints** — for each, decide between an API addition and a client-side
   composition, and record it. **Done, 2026-08-28**, decided in
   [ADR-0014](../adr/0014-where-the-console-s-remaining-screens-get-their-data.md) and built. The
   four went four different ways, which is the point of deciding them one at a time:

   - **`GET /model-policy` became an API composition.** `GET /models/active` was in the contract at
     Phase 4 with no handler, and building it over `model_registry` would have answered a permanent
     404: that table has never had a row written to it, and the registry of record is the one the
     scoring service loads from disk. The API now composes the model half from the scoring service
     and the policy half — the bands, which alert, at what priority — from its own validated
     configuration. **A scoring outage does not blank the screen**: the policy half is always
     knowable, and it is what somebody would be looking for during one.
   - **`GET /health` became a small API endpoint**, `GET /system/health`, covering what this service
     can observe: itself, PostgreSQL through the pool, and the scoring service with the state of the
     breaker in front of it. Always 200, because "the scoring service is down" is the answer rather
     than a failure to produce one.
   - **`GET /overview` became a client-side composition** of `GET /reports/alert-summary` and
     `GET /alerts`. An aggregate endpoint would have been a second implementation of risk-band
     counting beside the report that already does it, and the two would disagree the first time one
     changed. The cost is two requests and a screen that can be half-loaded, which the loading and
     error states already handle.
   - **`GET /reports` became the endpoint that already existed.** The screen counts a window with
     `GET /reports/alert-summary` and downloads the same window with `GET /reports/alerts.csv` —
     which had been tested and unused since Phase 5.

   **Three panels of invented numbers were deleted rather than sourced.** Throughput per hour,
   scoring latency percentiles, and consumer lag with dead-letter depth: nothing measured any of
   them, and three of the four belong to Prometheus and Kafka rather than to this API. The screens
   say what is missing and when it arrives. That is Phase 7's work, and a figure nobody measured is
   worse than none because somebody quotes it.

**Nothing here is a defect in the Phase 0 frontend.** It was scoped as a presentational foundation
against fixtures and it is exactly that. The divergence is what always happens when a mock is
written before the contract it stands in for, and finding it now — before the typed client is
written against the wrong shape — is the point of auditing first.
