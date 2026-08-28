# API migration audit — what the console asks for, and what the API answers

**Audited:** 2026-08-28 · **Auditor:** Claude · **Opens:** Phase 6 · **Status:** open

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
   handling. Prerequisite for everything else.
2. **Types and mapping** — `domain/types.ts` rewritten against the contract: real enums, the
   reference/identifier pair, `version`, `SYSTEM` as a role, and the removal of `ALLOWED_TRANSITIONS`
   and the client-supplied `actor`.
3. **Two small API additions**, each needing its own decision: legal targets on `GET /alerts/{id}`,
   and whatever resolves an `assigneeId` to a name.
4. **The four invented endpoints** — for each, decide between an API addition and a client-side
   composition, and record it. The overview screen is the one that matters; it is the landing page.

**Nothing here is a defect in the Phase 0 frontend.** It was scoped as a presentational foundation
against fixtures and it is exactly that. The divergence is what always happens when a mock is
written before the contract it stands in for, and finding it now — before the typed client is
written against the wrong shape — is the point of auditing first.
