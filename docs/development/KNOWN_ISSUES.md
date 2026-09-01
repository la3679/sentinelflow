# Known issues and technical debt

What is open in SentinelFlow as of **v1.0.0**, and what is known about each. Everything here is
deliberate — a decision taken with its cost written down, or a gap named rather than discovered.

Nothing on this page is a surprise to the maintainers, and nothing on it is hidden from a reader.
[`CHANGELOG.md`](../../CHANGELOG.md) carries the release-facing summary; this is the full list.

The security items are argued in full in [`THREAT_MODEL.md`](../security/THREAT_MODEL.md), which
also holds the ones not repeated here.

---

## Security, open and owned

- **A token cannot be revoked before it expires.** Thirty minutes is the whole of how long a
  withdrawn role keeps working. That is the cost of a stateless token and it is **accepted** rather
  than overlooked, with the expiry as the mitigation. Tracked as **T-08**; a revocation list is worth
  building only if the demo ever needs one.
- **`/actuator/prometheus` is unauthenticated**, because a scrape cannot hold a token that expires
  every thirty minutes. The series are aggregate counters and timers with bounded labels — no
  identifier, no amount, no payload — so what it discloses is the shape of the traffic. The real
  answer is a management port that is not published to the host.
  [ADR-0017](../adr/0017-protecting-the-ingestion-surface.md) §4 declines to bundle that fix,
  because it changes what Prometheus scrapes, what `compose.yaml` publishes, what `make smoke`
  asserts and what the runbooks say. Tracked as **T-04**.
- **Ingestion carries one shared key, not a per-caller identity.** `POST /api/v1/transactions`
  requires `X-API-Key` ([ADR-0017](../adr/0017-protecting-the-ingestion-surface.md) §1), compared in
  constant time, with no default and a 32-character floor — so it authenticates a caller without
  identifying _which_ caller, and an ingested transaction has no actor in the audit trail. The
  loopback binding is the containment behind it. Tracked as **T-09**.
- **The scoring service has no credential of its own.** It trusts any caller that can reach it on
  the Compose network. Tracked as **T-05**.
- **The rate limiter is per API instance.** Two instances behind a load balancer would permit twice
  the configured rate, and a restart forgets who was being limited. Right for a single-instance demo,
  wrong for a real edge, where the limiter belongs in front.
- **The local stack is not a deployment target.** It binds to `127.0.0.1`, holds only synthetic data,
  and has never been hardened for exposure
  ([ADR-0018](../adr/0018-deployment-and-the-local-first-strategy.md)).

## Verification that needs a person

Neither of these can be satisfied by an automated run, and **no automated result should be read as
evidence that either happened.**

- **Screen-reader behaviour is unverified.** axe runs across every route at two viewports and finds
  roughly a third of real accessibility issues. Every accessibility check in this repository is one
  of the cheaper two-thirds.
- **No manual authenticated walkthrough of the console has been done by a person.** Every screen's
  endpoints have been called with a real token, and `make verify-real-stack` drives the console
  against the running stack through its own sign-in form. Neither is somebody using it.

## Product gaps, named rather than discovered

- **Three operator actions have no endpoint** — reprocessing a dead-lettered event, reviving a
  `FAILED` outbox row, and rescoring a degraded assessment.
  [ADR-0005](../adr/0005-outbox-relay-mechanics.md) §5 fixes what each would have to be when it is
  built: administrator-only and audited. Until then
  [`RUNBOOKS.md`](../operations/RUNBOOKS.md) carries the manual procedure and says to record who ran
  it.
- **`audit_log` exists in the schema and nothing writes it.** Alert history is carried by
  `alert_actions`, which records the actor, the role and the moment of every mutation, so no history
  is missing — but a reader who sees `audit_log` should know it is unused rather than empty by
  coincidence. `AuditLogEntry` and `RegisteredModel` are the two persistence mappings with no
  callers.
- **Nothing navigates from a transaction to its alert.** The route that exists is alert to
  transaction.
- **Operators are seeded, not managed.** Nothing invites, disables, or changes the role of an
  operator; the directory an assignment picker reads is read-only
  ([ADR-0019](../adr/0019-resolving-an-assignee-to-a-person.md) §5).
- **A message that is not a readable envelope is never dead-lettered.** Deliberate: the dead-letter
  schema requires a valid envelope, and [ADR-0006](../adr/0006-event-schema-and-versioning.md) §4
  forbids copying unsanitised content onto an operational topic. It is logged with its coordinates
  and counted under `sentinelflow_consumer_undeliverable_total`.
- **`RetryStateTracker` measures the failures one process saw.** `attemptCount` and `firstFailedAt`
  in a dead-letter record come from the listener's own retry callbacks, so a restart or a rebalance
  mid-retry restarts the clock. They undercount in that case rather than overcounting.

## Testing and tooling

- **The console's branch coverage sits exactly on its floor**, at 17.00% against a gate of 17. It was
  18.61% when the gate was set, and the assignment work added branches the unit layer does not cover
  — the end-to-end suites cover that behaviour instead. The next console change that adds an
  untested branch fails the build, which is the intended consequence rather than a surprise.
  Thresholds: `apps/api` LINE 0.80 / BRANCH 0.70, `apps/scoring` 90% statements, `apps/web` 25%
  statements / 17% branches. **Every gate is a ratchet** — raised only when a change genuinely raises
  coverage, never lowered to go green.
- **Nothing asserts that the Compose stack's Kafka topics match the AsyncAPI contract.** The one-shot
  `kafka-topics` service creates the five the design names, and it is a second place the topic names
  are written — `EventTopics` holds the other. A drift between them shows up as a producer retrying
  `UNKNOWN_TOPIC_OR_PARTITION` behind a healthy stack, which is exactly the symptom that once hid for
  three phases. Today it is caught by `make smoke` and by the stack failing to publish, rather than
  by an assertion that names it.
- **`apps/web` lint reports 22 warnings and 0 errors**, measured 2026-09-01.
  `eslint-plugin-react-refresh` flags each TanStack Start route file's `Route` export, which
  `allowConstantExport` used to cover. The fix is `allowExportNames: ["Route"]` in
  `eslint.config.js`.
- **`noUnusedLocals` and `noUnusedParameters` are `false`** in `apps/web/tsconfig.json`.
- **CI is not path-filtered**, so every push runs every component. Deliberate
  ([ADR-0002](../adr/0002-monorepo-and-service-boundaries.md)); revisit when runtime justifies
  job-level change detection.

## Dependencies and images

- **The console loads its typeface from Google Fonts**, in `apps/web/src/routes/__root.tsx`. A stack
  that describes itself as local-first has one runtime dependency on a third party, and a browser
  with no network gets the fallback stack rather than the intended typography. Self-hosting is the
  fix. **This is a gap, not a decision.**
- **Two Dockerfiles pin an OpenSSL package version by hand** (`apps/scoring`, `apps/web`), to take
  the CVE-2026-14456 fix from the distribution before the base images ship it. Both blocks say how to
  check whether the base image has caught up, and should be deleted when it has. If a distribution
  rotates the pinned version out of its archive first, the build fails there with a clear message and
  the fix is the same deletion.
- **The published Temurin 25 image is one critical-patch build behind.** Containers build on
  `25.0.4+7`; `25.0.4.1+1` is available for local builds. Both are Java 25 LTS. Revisit when Adoptium
  publishes `25.0.4.1`.
