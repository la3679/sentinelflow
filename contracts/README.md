# Contracts

**These files are authoritative.** Where a contract and an implementation disagree, the contract is
right and the implementation is a defect.

Changing anything here means updating its producers, consumers, tests, and documentation **in the
same change**. See [ADR-0006](../docs/adr/0006-event-schema-and-versioning.md) and
[ADR-0007](../docs/adr/0007-money-identifiers-and-schema-migrations.md).

## Layout

| Path                                | What it is                                            |
| ----------------------------------- | ----------------------------------------------------- |
| `openapi/sentinelflow-api.yaml`     | The public `/api/v1` HTTP surface (OpenAPI 3.1)       |
| `openapi/sentinelflow-scoring.yaml` | The internal API-to-scoring contract (OpenAPI 3.1)    |
| `asyncapi/sentinelflow-events.yaml` | The five Kafka topics (AsyncAPI 3.0)                  |
| `schemas/`                          | JSON Schema 2020-12 for the envelope and each payload |
| `examples/`                         | Instances that **must** validate                      |
| `examples/invalid/`                 | Instances that **must be rejected**                   |

**Every document in `openapi/` is validated**, not a named one. The checker reads the directory, so
adding a document is enough to have it checked — the alternative is a second contract that nothing
verifies, which is exactly what "authoritative" is meant to rule out.

The scoring contract is **internal**. It is not reachable from a browser and is not part of `/api/v1`:
the API is the only backend the console talks to, so there is one authorization boundary, one audit
trail, and one place to rate-limit ([ADR-0002](../docs/adr/0002-monorepo-and-service-boundaries.md)).

## Checking them

```bash
make contracts-check
```

```powershell
.\scripts\dev\sf.ps1 contracts-check
```

It runs in CI on every push and pull request, and it does four things:

1. **Every schema compiles**, and its cross-file `$ref`s resolve locally. Remote schema loading is
   disabled — a contract that needs the network to validate is not a contract.
2. **Every valid example validates** against the envelope _and_ its payload schema. A schema
   nothing is checked against is a document, not a contract.
3. **Every invalid example is rejected.** These are the cases that matter: a validator that accepts
   everything passes silently. Deleting the `const: "NEW"` from `alert-created.v1.json` makes the
   check exit 1 and name the fixture — verified, not assumed.
4. **Every API document parses** and is internally consistent — both OpenAPI documents and the
   AsyncAPI one. The OpenAPI list comes from the directory, so a new contract is checked the moment
   it is added rather than when someone remembers to register it.

## The rules these encode

- **Money is a string.** `{"value": "1249.99", "currency": "USD"}`, never a JSON number.
  `JSON.parse` produces a `double`, so a JSON number is silently rounded by every JavaScript
  consumer before application code sees it. `examples/invalid/money-as-number.json` exists to keep
  that enforced rather than remembered.
- **Currency is never implicit.** An amount without one is not money.
- **Identifiers are UUIDv7.** `ACC-000123`-style references are human-readable handles, never keys.
- **Timestamps are ISO 8601 UTC**, and `occurredAt` is not `createdAt`.
- **`additionalProperties: false` everywhere**, so a producer cannot quietly add a field that
  consumers never learn about. This is stricter than the runtime rule, and deliberately so: at
  runtime consumers ignore unknown fields to tolerate a producer ahead of them, but a contract that
  accepts anything constrains nothing.
- **Nothing sensitive in a payload, ever** — including in dead-letter metadata. Events are
  long-lived, replayable, and readable by anything with topic access.

## Adding an event type

1. Add `schemas/<name>.v1.json`, referencing `common.v1.json` for shared primitives.
2. Add its `eventType` to the enum in `event-envelope.v1.json`.
3. Register it in `PAYLOAD_SCHEMA_BY_EVENT_TYPE` in `scripts/dev/check-contracts.mjs`.
4. Add a channel, a message, and an operation to the AsyncAPI document.
5. Add **both** a valid example and at least one invalid one. Without the negative case, the schema
   is untested.
6. Run `make contracts-check`.

## Changing an existing one

Backward-compatible changes only at a given `schemaVersion`: add optional fields, never remove,
rename, retype, or change the meaning of one. Changing what a field means while keeping its name is
the dangerous case, because no schema check catches it.

A breaking change gets a new `schemaVersion` and a new `.vN` topic, run alongside the old one until
its consumers have migrated. That is the only mechanism that does not require every consumer to
deploy simultaneously.

## Implementation status

Everything described here ships in **v1.0.0**. Some entries still carry the phase annotation they
were written with, which records when an endpoint or topic was delivered rather than whether it is
running. What is _not_ built is named in
[`docs/development/KNOWN_ISSUES.md`](../docs/development/KNOWN_ISSUES.md).
