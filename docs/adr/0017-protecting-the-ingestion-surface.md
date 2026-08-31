# ADR-0017 — Protecting the ingestion surface: a service credential, rate limits, and request bounds

- **Status:** Accepted
- **Date:** 2026-08-31
- **Related:** [ADR-0012](0012-operator-authentication.md),
  [ADR-0013](0013-console-to-api-cross-origin-access.md),
  [ADR-0005](0005-outbox-relay-mechanics.md),
  [`docs/security/THREAT_MODEL.md`](../security/THREAT_MODEL.md)

## Context

[ADR-0012 §5](0012-operator-authentication.md) left one thing undone on purpose and said so:
`POST /api/v1/transactions` is `permitAll`, because an operator's password buys nothing on a
machine-to-machine surface, and it "needs its own credential — a service account or a signed
request — together with the rate limits and payload bounds that belong beside it". It named Phase 8.
This is Phase 8.

The threat model published alongside this decision numbers what is open. Four of its eight items are
this ADR's:

| Item | What                                                                                                |
| ---- | --------------------------------------------------------------------------------------------------- |
| T-01 | Ingestion has no credential — spoofing (S-01), tampering (T-01), unattributable writes (R-03)       |
| T-02 | **No rate limiting anywhere.** Confirmed by search: nothing in `apps/api` or `apps/scoring` has any |
| T-03 | No maximum request size                                                                             |
| T-04 | `/actuator/prometheus` is open to whatever can reach the API                                        |

T-02 is the one worth pausing on. It is not only an ingestion problem: `POST /api/v1/auth/login`
compares a password with BCrypt and answers identically for a wrong password and an unknown username
(ADR-0012 §4, and a test asserts the two responses match). That symmetry is the right behaviour and
it is not a defence against someone trying a million passwords — nothing here is. **An unlimited
login endpoint is a worse hole than an unauthenticated ingestion endpoint**, because ingestion writes
synthetic rows and login is the door to the audit trail.

What already exists and is not being rebuilt: page-size caps refused rather than clamped
(`MAX_PAGE_SIZE` 200), the export's 10,000-row cap, the report window's 366 days, and idempotency on
ingestion. Those bound a **response**; nothing bounds a **request**.

## Decision

### 1. Ingestion authenticates with a service API key, not with an operator's token

`POST /api/v1/transactions` requires `X-API-Key`, matched against `SENTINELFLOW_INGEST_API_KEY`.
No default; the API refuses to start without one, exactly as `SENTINELFLOW_JWT_SECRET` does and for
the same reason. A minimum length is enforced at startup, because a short shared secret is weak in a
way nothing else would report.

**The comparison is constant-time.** `String.equals` returns as soon as two bytes differ, and the
time it takes is a function of how many leading bytes were right. That is a real oracle on a secret
compared on every request, and `MessageDigest.isEqual` costs nothing to use instead.

**Rejected: issuing the pipeline an operator token.** It would need a row in `user_credentials`, a
password, and a login every thirty minutes, and every one of those models a payment pipeline as a
person. ADR-0012 §2 made "the system principal cannot log in" structural precisely so that machine
identities and human ones do not share a door.

**Rejected: mutual TLS.** The correct answer for a real deployment and the wrong one for a demo that
runs on `make up`: it needs a certificate authority, a distribution story, and TLS in a stack that
deliberately has none.

**Rejected: a signed request (HMAC over body and timestamp).** Strictly better against replay, and it
needs the same shared secret plus clock agreement between two machines. The idempotency key already
makes a replayed ingestion harmless — a resubmitted key returns the original result — so the extra
machinery buys the one property this endpoint already has.

**Rejected: leaving it open and relying on the loopback binding.** That is what the situation already
is, and the threat model rates it Medium here and High deployed. A demo of an event-driven risk
platform that cannot show an authenticated ingestion boundary is missing the boundary.

**The key identifies a caller and grants exactly one thing:** the right to post a transaction. It is
not a role, it does not appear in the audit trail as an actor, and it cannot read anything. Ingested
transactions still record `IngestionSource.API` and nothing else; giving the key a name and putting
it in the audit trail is a larger change than this endpoint's honesty requires, and inventing an
actor row for it would make the audit trail say something it does not know.

### 2. Rate limiting is a token bucket in the API, per caller, per category

Three categories, each with its own bucket, because the right limit for a login attempt and the
right limit for a transaction feed are two orders of magnitude apart:

| Category  | Applies to                       | Default                   | Why                                                                                                                   |
| --------- | -------------------------------- | ------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `login`   | `POST /api/v1/auth/login`        | 10 per minute, burst 10   | A person typing a password fails a handful of times. A thousand attempts is not a person.                             |
| `ingest`  | `POST /api/v1/transactions`      | 600 per minute, burst 120 | The replay scenario posts in bursts; the limit is generous enough not to shape a demo and finite enough to be a limit |
| `default` | Everything else under `/api/v1/` | 300 per minute, burst 60  | An operator clicking a console cannot approach this. A loop can.                                                      |

Every number is configurable, and every one is a starting point rather than a measurement — Phase 9
benchmarks the pipeline and may move them, in which case this table moves with them.

**The limiter runs before authentication, and is keyed on what is available there.** A SHA-256
prefix of the `X-API-Key` header when one is present, and the remote address otherwise. Never a
client-supplied header beyond that: `X-Forwarded-For` is trivially forged and there is no trusted
proxy in this stack to make it meaningful — using it would let anybody spread themselves across as
many buckets as they liked.

**Before authentication rather than after, and that ordering is the decision.** Keying on the
authenticated principal would read better and would leave the hole that matters: a caller sending a
thousand requests a second with no token, or a wrong one, would be refused by the filter chain and
never counted, having already cost a signature verification each. Rate limiting exists to bound work
done on behalf of an unidentified caller, so it has to happen before the work that identifies them.

**The cost is stated: operators sharing an address share a bucket.** On a demo that binds to loopback
this is one machine, and the `default` category is set high enough that a person cannot reach it. On
a real edge the limiter would sit in front of a proxy that knows who is who, which is the same
sentence as "this belongs at an edge that does not exist here".

**Rejected: Bucket4j, or resilience4j's rate limiter.** Both are good, and both are a runtime
dependency and a transitive tree for something this build can express in a class small enough to
read. The counter-argument — that hand-rolled concurrency is where subtle bugs live — is fair, and
the answer is that the algorithm here is one `compareAndSet` loop over an immutable state record,
tested for refill arithmetic, for burst, and under concurrent load.

**Rejected: Redis or any shared store.** ADR-0012 kept the API stateless specifically so a second
instance needs nothing beside it. A shared rate-limit store would be the first thing to break that,
for a demo that runs one instance.

**Rejected: doing it in nginx or a gateway.** There is no gateway, and the console's nginx serves
static files on a different container from the API.

**The consequence is stated rather than hidden: the limit is per instance.** Two API instances behind
a load balancer would permit twice the configured rate. That is the correct trade for this system and
the wrong one for a real deployment, where the limiter belongs at the edge.

**A refusal is `429` with `Retry-After`**, in the same RFC 9457 problem shape as every other error,
and it never says what the limit is or how much of it is left. `X-RateLimit-*` headers are a
convenience for a well-behaved client and a progress bar for a badly behaved one; a caller that is
being limited needs to know to come back later, not how close it got.

### 3. A request body has a maximum size, refused with `413`

`SENTINELFLOW_MAX_REQUEST_BYTES`, default **64 KiB**, on every request under `/api/v1/`. The largest
legitimate body this API takes is a transaction — a few hundred bytes — and the largest a person
writes is a 2,000-character alert note.

**Both halves are checked**, because either alone is a hole: a declared `Content-Length` above the
cap is refused before a byte is read, and the stream itself is wrapped so a chunked request with no
declared length is cut off at the same number. Trusting the header alone would be a bound a client
opts into.

**Not `server.tomcat.max-http-form-post-size`.** That applies to form encoding, and nothing here
posts a form; a reader who saw it set would reasonably conclude JSON was covered, and it is not.

### 4. The management surface stays open, and the reason is written down rather than fixed

T-04 — `/actuator/prometheus` reachable without a credential — is **not** closed by this ADR. Moving
the actuator to a management port that is not published to the host is the right fix, and it means
changing what Prometheus scrapes, what `compose.yaml` publishes, what the smoke test asserts and what
the runbooks say. That is its own change with its own tests, and bundling it here would make one
commit out of two decisions.

What is decided here is that it stays scoped: the endpoint discloses aggregate counters and timers
with closed label sets, which ADR-0016 §2 already requires, and there is a test that the actuator
exposes only health, info and prometheus. It remains T-04 in the threat model, still open, still
owned by Phase 8.

## Consequences

- **Ingestion has a caller.** Anything that can reach the API can no longer write to the database and
  the outbox; something that holds the key can. `make bootstrap` generates it into the git-ignored
  `.env` alongside the JWT secret and the demo password.
- **Every existing ingestion caller must be updated in the same change**: the smoke test, the replay
  and generator paths that post over HTTP, the compose environment, the README, and the OpenAPI
  contract. A credential added without them is a broken demo.
- **The login endpoint stops being a free brute-force target**, which is the largest single security
  improvement in this ADR and is not the one its title leads with.
- **The limiter holds state in memory**, bounded, and a restart forgets it. A caller who was being
  limited gets a clean slate after a deployment; that is acceptable for a demo and would not be for a
  real edge.
- **A limit is a new way for a legitimate demo to fail.** The numbers are deliberately generous, the
  refusal says to retry and when, and `docs/operations/RUNBOOKS.md` gains the entry for "everything is
  suddenly 429".
- **A 64 KiB cap is a contract change.** It goes into the OpenAPI document as a `413` response with
  the number in it, because a limit a client cannot discover is a limit it will hit in production.

**Revisit if:** the demo is ever run as more than one API instance, at which point per-instance
limiting is wrong and the limiter belongs at an edge that does not exist yet; Phase 9's benchmarks
show a default shaping legitimate throughput; or the ingestion caller ever needs to be more than one
identity, at which point a single shared key stops being enough and the question becomes a service
account with a lifecycle — which is ADR-0012's mechanism, amended, rather than a third scheme.
