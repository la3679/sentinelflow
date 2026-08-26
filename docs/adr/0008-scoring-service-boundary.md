# ADR-0008 — Scoring-service boundary, failure behaviour, and who owns the threshold

- **Status:** Accepted
- **Date:** 2026-08-26
- **Related:** [ADR-0002](0002-monorepo-and-service-boundaries.md),
  [ADR-0004](0004-python-runtime-and-model-stack.md),
  [ADR-0006](0006-event-schema-and-versioning.md)

## Context

[ADR-0002](0002-monorepo-and-service-boundaries.md) put risk scoring in a separate Python service
and said nothing about how the two halves talk. Phase 3 built the consumer that will call it and
deliberately shipped no handler, so the boundary is still open — and it is about to be closed by
whichever line of code is written first, which is the wrong way to decide it.

Four questions have to be answered before Phase 4 starts, because each is visible in stored data or
in how the system behaves when something is down:

1. Does the API call scoring synchronously, or is scoring another consumer on the bus?
2. What happens to a transaction when scoring is unavailable?
3. What are the timeout and retry budgets, given that a consumer's retry blocks its partition?
4. Which service decides that a score is high enough to raise an alert?

Some of this is already constrained. [ADR-0006](0006-event-schema-and-versioning.md) §4 classifies a
scoring 5xx as retryable and a schema failure as not; Phase 3's consumer retries on a bounded,
fully-jittered schedule that blocks the partition while it runs; and `RiskAssessment` already has a
`degraded()` factory that nothing constructs, which is the schema saying an answer without a model
score is a state the design expects.

## Decision

### 1. The consumer calls scoring over HTTP, synchronously, inside its handler

`transaction.created.v1` is consumed by the API. Its handler calls `POST /v1/score` and writes the
assessment in the same transaction as the idempotency-ledger row that Phase 3 built.

**Not a second event round trip.** Scoring as a Kafka consumer publishing `risk.assessed.v1` back is
the more fashionable shape and was rejected for three specific reasons rather than on taste:

- **The correlation between a transaction and its assessment would become eventual**, and the
  console's central promise is that an analyst can see why a transaction was scored the way it was.
  A round trip means a window where the transaction exists and its assessment does not, and every
  screen has to render that state.
- **Idempotency would need solving twice.** The consumer's ledger makes "handled at most once" a
  property of one transaction; splitting the work across a second topic means a second ledger, a
  second deduplication key, and a second set of partial-failure states.
- **The Python service would need Kafka.** It currently needs nothing but HTTP, and a broker client,
  a consumer group and an outbox of its own is a large amount of machinery for one call.

**The cost, stated plainly:** the API's ability to assess is coupled to the scoring service being
reachable. That is what §2 is for, and it is a smaller coupling than it looks — ingestion is
already decoupled, because the transaction is accepted, committed and published before any of this
runs. **Scoring being down never rejects a transaction.** It delays or degrades its assessment.

**The contract is versioned and lives in `contracts/openapi/`** alongside the public API, and both
sides test against the file rather than against each other.

#### What crosses the boundary, and why the scoring service stays stateless

The request carries **the transaction, and a bounded account context the API computes**. The
response carries a model score, the feature and model versions, reason contributions, and an
inference duration.

This follows from something [ADR-0002](0002-monorepo-and-service-boundaries.md) already decided and
that is easy to walk into by accident. Several of the features this project needs are historical —
transaction counts over the last minute, five minutes and hour; amount sums over the last hour and
day; time since the previous transaction; whether this merchant, device or country is new for the
account. **The scoring service has no database**, and giving it one would make it a second system of
record for transactions that `apps/api` already owns.

So the API, which has that history, computes the context and sends it. The scoring service turns a
context into a feature vector and a feature vector into a score, and holds no state between
requests.

Three consequences, all deliberate:

- **The context is bounded and versioned**, like everything else on the wire. A request whose size
  grows with an account's history is a denial-of-service primitive, and an unversioned one cannot be
  changed without deploying both services at the same instant.
- **A scoring instance is disposable.** No warm cache to lose, no local state to reconcile, and
  scaling it is adding a replica.
- **The API pays for the context.** It is one indexed read per transaction over
  `transactions_account_occurred_idx`, which exists for exactly this and which
  `MigrationIT` already asserts is still there.

**Nothing that would only be known after an analyst's decision may enter the context.** That is the
leakage rule from §12.3 of the build prompt expressed at the boundary rather than only in the
training script: a feature the model could not have had at scoring time makes an evaluation
worthless, and the boundary is the last place that can be enforced structurally.

### 2. When scoring is unavailable, the assessment is written degraded

Three outcomes, and they are genuinely different states rather than three flavours of failure:

| Situation                                       | Outcome                                                             |
| ----------------------------------------------- | ------------------------------------------------------------------- |
| Scoring answers                                 | `RiskAssessment.scored(...)` — rule score, model score, final score |
| Scoring is unreachable, or 5xx, past its budget | `RiskAssessment.degraded(...)` — rule score only, `degraded = true` |
| Scoring rejects the request as invalid          | Non-retryable; the event is dead-lettered (ADR-0006 §4)             |

**The rules baseline is computed in the API, not in the scoring service.** That is what makes a
degraded assessment worth writing: it is a real answer from a transparent rule set, not a null with
a flag on it. It also means the demo has something to show when the model is down, which is the
honest version of "resilient" rather than a retry loop that eventually gives up.

**A degraded assessment is a first-class record and says so.** `degraded = true`, no
`model_version`, no `model_score`, and the reason codes are the rules' own. The schema's `CHECK`
already enforces that combination; this ADR is why it exists. The console must render it as
"scored by rules, model unavailable" and never as an ordinary score — a degraded assessment
presented as a normal one is the system lying about its own confidence.

**A degraded assessment is not permanent.** It records the state at the time it was made. Rescoring
is a deliberate, audited operation like the other two recovery paths in this system (ADR-0005 §5),
and is not automatic: a background job silently upgrading assessments would change decisions an
analyst may already have acted on.

### 3. Budgets are small, and they are small because a consumer's retry blocks its partition

| Setting               | Value                                                    | Why this number                                                                |
| --------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------ |
| Connect timeout       | 1 s                                                      | A reachable service on the compose network connects immediately                |
| Read timeout          | 2 s                                                      | Inference is arithmetic over a feature vector; a slow answer is a sick service |
| Retries within a call | 2                                                        | Three attempts total, for a restart or a dropped connection                    |
| Backoff               | Full jitter, 100 ms base, 1 s ceiling                    | Same distribution as everywhere else in this system                            |
| Circuit breaker       | Opens after 5 consecutive failures, half-open after 30 s | Stops paying the timeout per record                                            |

**The whole budget is under ten seconds by construction**, because Phase 3's consumer retries by
blocking its partition — everything queued behind a record waits for it. A generous HTTP budget
inside a blocking consumer multiplies: the consumer's own five deliveries times a long per-call
budget is a partition stalled for minutes over one unreachable dependency.

**The circuit breaker is the part that matters at scale**, and it is not decoration. Without it,
every record in the backlog pays the full timeout before degrading, so a scoring outage converts
directly into consumer lag proportional to traffic. With it, the first few records pay the cost and
the rest degrade immediately.

**A 4xx is never retried.** It means this request will not become valid, and ADR-0006 §4 already
says so. It is dead-lettered so it is visible rather than absorbed as a degraded assessment — a
contract mismatch between two services in one repository is a defect to fix, not a condition to
tolerate.

### 4. The API owns the threshold; the scoring service owns the score

The scoring service returns a model score, a feature version, a model version, reason contributions
and an inference duration. **It never decides whether an alert should exist.**

Alerting policy is configuration in the API, validated at startup, versioned as `policy_version`,
and persisted on every assessment alongside the model and feature versions.

Three reasons, in order of weight:

- **A threshold is a business decision and a model score is a measurement.** They change on different
  schedules and for different reasons, and a threshold that ships inside a model artifact cannot be
  changed without retraining.
- **Every decision must be reconstructible.** Storing `model_version`, `feature_version` and
  `policy_version` on the assessment means an analyst can be told exactly which model and which
  policy produced an alert months later. If the threshold lived in the model, "which policy" would
  have no answer independent of "which model".
- **The rules baseline and the model must share one threshold**, otherwise a degraded assessment and
  a scored one are not comparable, and the band on a transaction would mean something different
  depending on whether the model happened to be up.

**Bands are `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`**, mapped from the final score by that configuration,
and the mapping is tested rather than assumed.

#### This refines ADR-0002's ownership table rather than contradicting it

[ADR-0002](0002-monorepo-and-service-boundaries.md) §3 assigns "evaluation metrics and thresholds"
to `apps/scoring`. Read carelessly that is the opposite of what this section says, so the two words
are separated here rather than left to be reconciled by whoever hits them next.

**There are two thresholds, and they are different objects.**

| Threshold                                                      | Owner          | Changes when                                     |
| -------------------------------------------------------------- | -------------- | ------------------------------------------------ |
| The **operating point** a model is evaluated at and ships with | `apps/scoring` | The model is retrained or re-evaluated           |
| The **alerting policy** applied to a final score at runtime    | `apps/api`     | The business changes what is worth investigating |

The first is a property of a model and belongs in its model card, next to the precision and recall
measured at it. The second is a runtime decision applied to a final score that also folds in a rule
score the model never saw, and it must apply identically whether or not the model answered at all —
which is impossible if it lives in the model.

**They may legitimately differ**, and when they do, the model card's operating point is a
recommendation and the policy is what actually ran. Both are persisted on the assessment, so which
is which is never a matter of inference.

## Alternatives considered

**Scoring as a Kafka consumer publishing `risk.assessed.v1`.** Rejected for the three reasons in §1.
Worth revisiting if scoring ever becomes slow enough that a synchronous call is untenable, or if a
second consumer of transactions appears that has nothing to do with risk — at which point the
round trip stops being extra machinery and starts being the natural shape.

**gRPC instead of HTTP and JSON.** Rejected for v1. It would buy a schema and lower serialisation
cost; it costs a second contract language, generated code in two build systems, and a wire format
nobody can read with `curl` during a demo. At this volume the serialisation cost is not measurable
and the debuggability is worth more.

**Calling scoring from the ingestion request path.** Rejected outright. It would put a model
inference on the latency budget of an endpoint whose entire job is to accept a transaction durably
and quickly, and it would make ingestion fail when scoring is down — the exact coupling the outbox
exists to avoid.

**The scoring service returning a band rather than a score.** Rejected: see §4. It also makes the
service's contract change every time a business threshold moves.

**Giving the scoring service its own read access to the transaction database**, so it could compute
historical features itself. Rejected: it would make two services systems of record for the same
table, couple the Python service to a schema owned by the Java one, and put a second consumer on the
connection pool — all to avoid sending a bounded context on a request that is already being made.

**No circuit breaker, just the timeout.** Rejected. A timeout bounds one call; a scoring outage is
every call, and paying the bound repeatedly turns a dependency failure into unbounded consumer lag.

## Consequences

**Positive.** A transaction and its assessment are one transaction, so there is no window where one
exists without the other. Scoring being down degrades an assessment rather than losing it, and the
degradation is visible in the data rather than implied by an absence. Every decision carries the
model, feature and policy versions that produced it. The scoring service stays HTTP-only, with no
broker client, no consumer group and no outbox of its own.

**Negative.** The API's assessment path is coupled to the scoring service's availability, and the
circuit breaker is now load-bearing rather than a nicety. A degraded assessment is a second state
that every screen, every export and every report has to render honestly. Rescoring is manual, so a
scoring outage leaves a durable mark on the data until someone acts on it — which is the intended
trade, but it is a trade.

**Revisit if:** inference latency grows past the read timeout for reasons that are not a fault;
a consumer of transaction events appears that is unrelated to risk; or measured consumer lag during
a scoring outage shows the circuit breaker's thresholds are wrong. Phase 9 measures the last of
these; until then no latency figure in this ADR is claimed as measured.
