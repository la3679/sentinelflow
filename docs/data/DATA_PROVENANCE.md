# Data provenance

Where every row in a SentinelFlow database comes from, and what it is not.

**SentinelFlow contains no real data of any kind.** No dataset has been downloaded, imported,
converted, or embedded. There is no third-party data in this repository and no third-party data
licence to comply with, because every row is produced by code written here.

> SentinelFlow is an independent educational project. It is not a bank system, not a compliance
> product, and not a fraud-decision engine. Nothing here describes any real institution, customer,
> merchant, or transaction.

---

## 1. Sources

| Source                                     | Status                     | Used                                     |
| ------------------------------------------ | -------------------------- | ---------------------------------------- |
| SentinelFlow deterministic seed loader     | **in use**                 | Yes — the only source of data today      |
| SentinelFlow scenario generator            | **planned, Phase 4**       | Not written yet                          |
| [IBM AMLSim][amlsim] (Apache-2.0)          | evaluated, **not adopted** | No data, no code, no concepts taken      |
| [`gen-fraud-graph`][genfraud] (Apache-2.0) | evaluated, **not adopted** | No data, no code, no concepts taken      |
| [Fraud Detection Handbook][handbook]       | research reading only      | **No code.** See the licence note below. |

[amlsim]: https://github.com/IBM/AMLSim
[genfraud]: https://github.com/SantanderAI/gen-fraud-graph
[handbook]: https://github.com/Fraud-Detection-Handbook/fraud-detection-handbook

### Licence note on the Fraud Detection Handbook

Its notebook code is GPLv3 and its prose and images are CC BY-SA 4.0. Neither licence is compatible
with this Apache-2.0 repository. **No code, text, or image from it has been copied.** Where its
research informs a choice — imbalanced-classification evaluation, why accuracy is close to
meaningless on this class balance — the conceptual debt is cited in
[`docs/research/RESEARCH_LOG.md`](../research/RESEARCH_LOG.md) and the implementation is original.

---

## 2. The seed loader

`apps/api/src/main/java/io/github/la3679/sentinelflow/api/seed/` — generator version **1.0.0**.

### What it writes

Parties only: customers, their accounts, merchants, and four demo analyst logins. The traffic that
runs over them comes from the scenario generator in §3.

| Profile | Customers | Merchants | Accounts per customer | Accounts | Analysts |
| ------- | --------- | --------- | --------------------- | -------- | -------- |
| `CI`    | 20        | 8         | 1                     | 20       | 4        |
| `DEMO`  | 200       | 40        | 2                     | 400      | 4        |
| `LOCAL` | 2 000     | 150       | 3                     | 6 000    | 4        |

### What it deliberately does not write

There is no name, address, date of birth, national identifier, email address, telephone number, or
card number anywhere in the dataset, **because the schema has no column for any of them**. That is
the primary control: this is not personal data that has been anonymised, it is a schema that never
had somewhere to put personal data. `DeterministicSeedLoaderIT` asserts that no such column has
appeared on `customers` or `accounts`, so adding one is a failing test rather than a quiet
regression.

Merchant names are assembled from two invented word lists (`Aster`, `Bramble`, `Thistle` … ×
`Provisions`, `Works`, `Depot` …) and match no real business. Country codes are ISO 3166-1 alpha-2
and category codes are ISO 18245 — a jurisdiction and a merchant category are properties of a
transaction, not of a person.

### Reproducibility

Everything that varies is drawn from one `java.util.Random` seeded from
`SENTINELFLOW_SEED` (default `20260826`). `java.util.Random` is specified exactly by the JDK rather
than left to the implementation, and only the single-argument `nextInt(int)` form is used, so the
same seed produces the same dataset on any machine and any JDK.

The loader returns a `SeedManifest` carrying the generator version, the seed, the profile, the row
counts, and a **SHA-256 checksum over every generated business reference in generation order**.

The checksum covers references, not database identifiers. Identifiers are UUIDv7 and embed the
wall-clock millisecond they were minted, so two identical runs necessarily differ there; hashing
them would prove nothing. References are the deterministic part, and they are what a reproduction
claim is actually about. Two runs of the same seed at the same profile also produce identical row
_contents_ — countries, tiers, balances, merchant names — which `DeterministicSeedLoaderIT` asserts
directly rather than through the checksum.

### Running it

```bash
SENTINELFLOW_SEED_ENABLED=true SENTINELFLOW_SEED_PROFILE=CI docker compose up api
```

Off by default in every environment. A second run over a database that already holds demo customers
writes nothing and says so, so running it twice is safe.

`make seed` (or `.\scripts\dev\sf.ps1 seed`) does both halves — parties, then traffic — against a
running stack. `make replay` remains in progress; see §3.

---

## 3. The scenario generator

`apps/api/src/main/java/io/github/la3679/sentinelflow/api/seed/scenario/` — generator version
**1.0.0**.

### What it writes

Transactions, over the parties the seed loader wrote. Ordinary background traffic with detectable
shapes planted in it.

| Profile | Background transactions | Planted shapes |
| ------- | ----------------------- | -------------- |
| `CI`    | 200                     | 12             |
| `DEMO`  | 2 000                   | 30             |
| `LOCAL` | 20 000                  | 200            |

Each planted shape contributes between one and seven transactions of its own, so the total is a
little above the background count. The manifest reports what was actually generated.

**Shapes are patterns, not single rows.** Every one of them needs history to see, which is the point
of generating data rather than writing a few suspicious-looking rows: a rule that only has to notice
one large amount can be written without any of this and would say nothing about whether the pipeline
works.

| Shape                  | What it is                                                             |
| ---------------------- | ---------------------------------------------------------------------- |
| `NORMAL`               | Background: familiar merchants, familiar devices, unremarkable amounts |
| `VELOCITY_BURST`       | Seven purchases on one account inside ninety seconds                   |
| `AMOUNT_SPIKE`         | One purchase fifteen to thirty times that account's own baseline       |
| `CARD_TESTING`         | Six trivial card-not-present authorisations, then one large            |
| `GEO_IMPROBABLE`       | Two card-present purchases, twenty minutes and a continent apart       |
| `ACCOUNT_DRAIN`        | Three movements emptying most of a balance inside an hour              |
| `OFF_HOURS_NEW_DEVICE` | A purchase between 02:00 and 04:00 from a device never used before     |

The background is not filler. An account's ordinary spending is drawn around a baseline derived from
its own reference, it favours a handful of merchants it has used before, and it uses one of two
devices — which is what makes "a merchant this account has never used" and "a device this account
has never used" mean anything when a shape breaks the habit. Traffic where every account behaved
identically would make a velocity feature trivially predictive and any evaluation of it meaningless.

**Not yet represented, and worth saying so:** rapid fan-in from multiple sources, which §8.3 of the
build prompt lists. `transactions` records an account and a merchant and has no counterparty account
column, so a transfer between two accounts in this system is one row on one account. Fan-in is not
expressible without a schema change, and a schema change to satisfy a generator would be the wrong
way round. Rounded-value transfers and rapid fan-out are expressible and are not implemented yet.

### Labels never enter the database

The generator knows which transactions it planted. **The running system does not, and must not.**

A label column on `transactions` would be information that only exists after the fact, sitting next
to the row a model is about to be asked to score — the textbook leak, and one that makes every
evaluation downstream of it worthless. What the operational schema records instead is an analyst's
verdict, in `analyst_feedback`, which is a different thing arrived at honestly.

The label distribution lives in the manifest and nowhere else, as counts rather than as a mapping:
enough to say "this dataset contains twelve card-testing runs" without being a lookup table that
could be joined back to rows. `ScenarioLoaderIT` asserts against `information_schema` that no column
named for a label, a scenario, or fraud has appeared on `transactions`.

### It writes through the ingestion path

Generated traffic goes through `TransactionWriter` — the same code a posted transaction goes
through — so it gets the same validation, the same reference resolution, the same idempotency
constraint, and the same outbox row. It therefore flows through the relay and the consumer exactly as
real traffic does, which is what makes a seeded demo a demonstration of the pipeline rather than a
table full of rows the pipeline never saw.

`ingestion_source` is `GENERATOR`, so generated traffic stays distinguishable from anything a client
posted, for ever, in the row itself.

### Reproducibility

One `java.util.Random` seeded from `SENTINELFLOW_SEED`, and no clock: the window's end instant is
passed in rather than read, so the generator itself is a pure function of its inputs.
`ScenarioGeneratorTests` asserts that two runs are equal record-for-record, and that two calls on one
instance agree.

The manifest carries the generator version, the seed, the profile, the counts, the label
distribution, and a **SHA-256 over every generated request in order** — offsets rather than instants,
references rather than identifiers, the amount as the string that was sent. Nothing the database
assigned goes in: transaction identifiers are UUIDv7 and embed the millisecond they were minted, and
`transaction_reference` comes from a sequence that gaps whenever a write rolls back. Either would
make two identical runs differ and the checksum prove the opposite of what it claims.
`ScenarioLoaderIT` asserts the checksum survives a fresh database and a window moved by a day.

### Running it

```bash
make seed
```

Recreates the API with `SENTINELFLOW_SEED_ENABLED=true`, seeds, then recreates it without — so a
later restart does not reseed. On Windows, `.\scripts\dev\sf.ps1 seed`.

Running it twice is a no-op. Every generated idempotency key is derived from the seed, so
`transactions_idempotency_unique` rejects a second load; the fast path that skips when generated
traffic is already present is an optimisation, and `ScenarioLoaderIT` proves the constraint is what
actually guarantees it by defeating that path.

### `make replay` is not implemented

The transaction shapes it would replay are generated today by `make seed`. Replay's own value is in
the operational scenarios §8.3 lists — a temporary scoring-service outage, a malformed event reaching
the dead-letter path — and neither exists to replay until the scoring client does. It lands with the
pieces it demonstrates rather than ahead of them.

---

## 4. Committed files

**None.** No dataset file, sample extract, or model artifact is committed. Every row is generated on
demand from the seed above, which is why there is no file list and no checksum table here.

`.gitignore` excludes generated bulk data and model artifacts. If a small reviewed sample is ever
committed for documentation, it is listed here with its size and SHA-256 before it is committed,
not after.

---

## 5. Limitations of synthetic data

Recorded so that no result measured on it is over-claimed:

- **The label distribution is chosen, not observed.** Any precision, recall, or PR-AUC figure
  measured against it describes the generator's assumptions at least as much as the model's ability.
- **The dependence structure is shallow.** Real fraud involves coordination between accounts,
  merchants, and devices over time. A generated population has whatever structure it was written to
  have and no more.
- **There is no distribution shift.** Real transaction populations drift; a fixed seed does not.
- **No result here transfers to a real institution.** Nothing measured on this data is evidence
  about any production system, and no figure from it should be presented as one.

Every performance number this project ever reports carries the command, the date, and the profile
it was measured on. See [`../testing/TEST_RESULTS.md`](../testing/TEST_RESULTS.md).

---

## 6. Removing or reproducing the data

Reproduce: set `SENTINELFLOW_SEED` and `SENTINELFLOW_SEED_PROFILE` to the values in the manifest and
run the loader against an empty database. The manifest checksum from the new run must match the old
one; if it does not, the generator version changed and the manifest says which.

Remove: `make reset-demo` drops the local stack's volumes, which is the only place generated data
ever exists. Nothing to purge from the repository, because nothing was ever committed.
