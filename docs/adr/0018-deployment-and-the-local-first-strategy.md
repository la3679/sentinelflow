# ADR-0018 — Deployment: local-first, and no hosted demo for v1

- **Status:** Accepted
- **Date:** 2026-09-01
- **Related:** [ADR-0002](0002-monorepo-and-service-boundaries.md),
  [ADR-0012](0012-operator-authentication.md),
  [ADR-0013](0013-console-to-api-cross-origin-access.md),
  [ADR-0017](0017-protecting-the-ingestion-surface.md),
  [`docs/security/THREAT_MODEL.md`](../security/THREAT_MODEL.md),
  [`docs/operations/RUNBOOKS.md`](../operations/RUNBOOKS.md)

## Context

SentinelFlow has been local-first since the first commit, and it has never been written down. That
is the gap this record closes: the decision has been load-bearing for nine phases — it shapes the
threat model, the security posture, what the README may claim, and what `compose.yaml` publishes —
while existing only as a habit and a sentence in the README.

The pressure to reverse it is real and worth naming honestly. **A portfolio project with a live
link gets looked at, and one that asks a reader to install Docker mostly does not.** Every argument
below is made against that, not in ignorance of it.

Three facts constrain the choice.

**The stack is ten containers.** PostgreSQL, Kafka in KRaft mode, the Spring Boot API, the FastAPI
scoring service, an nginx-served console, a one-shot topic creator, Prometheus, Grafana, an
OpenTelemetry Collector and Tempo. That is not a shape that fits a free tier, and the parts that
would have to be dropped to make it fit — the broker, the collector, the trace store — are the parts
that make the project worth reading.

**Nothing in it was built to be exposed.** The threat model's four trust boundaries all assume a
single machine. Ingestion carries one shared key rather than a per-caller identity
([ADR-0017 §1](0017-protecting-the-ingestion-surface.md)); the rate limiter is an in-memory token
bucket per API instance, so two instances behind a load balancer permit twice the configured rate
([ADR-0017 §2](0017-protecting-the-ingestion-surface.md)); a token cannot be revoked before its
thirty minutes expire ([ADR-0012](0012-operator-authentication.md)); and `/actuator/prometheus` is
unauthenticated, tracked as T-04. Each is the right trade for a demo on one machine and the wrong
one for anything reachable from a network.

**A hosted demo would cost money, continuously, for as long as it is worth having.** Nothing in this
repository provisions a billable resource, and the first thing that did would need an owner, a
budget and a shutdown story.

## Decision

### 1. The local Docker Compose environment is the only supported deployment target

`compose.yaml` is the deployment. `make up` is the deploy command. There is no staging, no
production, no Kubernetes manifest, no Terraform, and no cloud account.

**This is a scope decision, not a capability claim.** The project demonstrates an event-driven
system end to end; it does not demonstrate operating one in production, and it should not pretend
otherwise by shipping manifests nobody has run.

### 2. Every published port binds to the loopback interface by default

`"${SENTINELFLOW_BIND_ADDRESS:-127.0.0.1}:${PORT}:CONTAINER_PORT"`, on every service that publishes
anything.

**This is the clause most likely to be undone by accident**, because `"5432:5432"` looks equivalent
and is not: it is Docker's shorthand for every interface, and it will happily publish PostgreSQL to
a coffee-shop network. An earlier version of this repository had exactly that, and a security claim
in a document rested on the opposite for weeks.

**Verify it with `docker port`, never by reading `compose.yaml`.** On the stack as it runs today:

```text
postgres     5432/tcp  -> 127.0.0.1:5432
kafka        29092/tcp -> 127.0.0.1:29092
api          8080/tcp  -> 127.0.0.1:8080
web          8080/tcp  -> 127.0.0.1:5173
scoring      8000/tcp  -> 127.0.0.1:8000
prometheus   9090/tcp  -> 127.0.0.1:9090
grafana      3000/tcp  -> 127.0.0.1:3000
```

`SENTINELFLOW_BIND_ADDRESS` exists so that a developer who genuinely needs to reach the stack from
another device can widen it deliberately, in one place, having read this. It is not a default and it
must not become one.

### 3. No hosted demo, and the README does not link to one that does not exist

The README says there is no hosted demo and says why. **It never links to a URL that is dead, paused,
asleep, or serving a different version of the project**, which is the ordinary fate of a portfolio
demo six months after it is deployed.

The honest substitute is the one already built: five screenshots generated from the production
bundle by the end-to-end suite so they cannot drift, a quick start that a clean-clone verification
has actually run, and a `make replay` that demonstrates the failure behaviour rather than describing
it.

### 4. Every image is built to be deployable anyway, and that is deliberate

Local-first is not an excuse for images that could only ever run here. Each of the three runs as a
**non-root user**, declares a **health check**, is scanned by **Trivy** on every push with any
fixable HIGH or CRITICAL failing the build, and ships with a **CycloneDX SBOM**. CI asserts the
non-root user against the **built image** rather than trusting the Dockerfile.

Every secret comes from the environment, has **no default**, and stops startup when it is missing —
`make bootstrap` generates five of them into a git-ignored `.env`. Compose refuses to start rather
than falling back to something guessable.

So the artefacts are production-shaped. What is missing is the surrounding system — an identity
provider, a distributed rate limiter, a secret manager, a revocation story — and this ADR is the
record that their absence is known rather than overlooked.

### 5. What would have to be true before this is revisited

Written down so a future session weighs the decision rather than re-deriving it:

- **T-04, T-05 and T-09 closed**, or consciously accepted with a compensating control: the open
  metrics endpoint, the scoring service's missing credential, and ingestion's shared key.
- **The rate limiter moved in front of the API**, because an in-memory bucket per instance is not a
  limit once there is more than one instance.
- **A revocation story**, because thirty minutes of a withdrawn role is a local-demo trade.
- **An owner and a budget**, with a shutdown story written before the first resource is created.
- **A decision about the data**, which is trivial only because it is synthetic today.

Any one of these left open is a reason not to deploy. All five closed is a different project's
Phase 1.

## Consequences

- **A reader must install Docker to see it run.** That is a real cost and it is accepted. The
  screenshots, the verified quick start and the recorded benchmark exist to make the cost worth
  paying rather than to disguise it.
- **The threat model stays honest**, because its trust boundaries match the only environment that
  exists. A threat model written for a deployment nobody has is fiction.
- **The README can make stronger claims**, not weaker ones, because every command it publishes runs
  on the environment it publishes them for — and a clean-clone verification checks that.
- **`make reset-demo` is the whole disaster-recovery story**, and this ADR is where that is
  recorded. There is no backup and no restore, because the data is synthetic and every row of it is
  regenerable by `make seed` from a recorded profile and seed. Writing a backup procedure nobody
  would run, for data nobody would miss, would be documentation theatre.
- **Nothing here bills anybody.** A repository that cannot create a billable resource cannot leave
  one running.

## Revisit if

- The project acquires a reason to be reachable — a collaborator who cannot run Docker, or a
  demonstration that has to be given rather than cloned.
- The five conditions in §5 are met, in which case this ADR is superseded rather than amended.
- Docker Compose stops being a reasonable way to run ten containers on a developer machine.
