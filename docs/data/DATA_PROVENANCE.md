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

| Source                                    | Status                     | Used                                     |
| ----------------------------------------- | -------------------------- | ---------------------------------------- |
| SentinelFlow deterministic seed loader    | **in use**                 | Yes — the only source of data today       |
| SentinelFlow scenario generator           | **planned, Phase 4**       | Not written yet                          |
| [IBM AMLSim][amlsim] (Apache-2.0)         | evaluated, **not adopted** | No data, no code, no concepts taken      |
| [`gen-fraud-graph`][genfraud] (Apache-2.0)| evaluated, **not adopted** | No data, no code, no concepts taken      |
| [Fraud Detection Handbook][handbook]      | research reading only      | **No code.** See the licence note below. |

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

Parties only: customers, their accounts, merchants, and four demo analyst logins. **No
transactions.** Transactions, scenarios, and labelled suspicious patterns arrive with the generator
in Phase 4 and are built on top of these parties.

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
*contents* — countries, tiers, balances, merchant names — which `DeterministicSeedLoaderIT` asserts
directly rather than through the checksum.

### Running it

```bash
SENTINELFLOW_SEED_ENABLED=true SENTINELFLOW_SEED_PROFILE=CI docker compose up api
```

Off by default in every environment. A second run over a database that already holds demo customers
writes nothing and says so, so running it twice is safe.

`make seed` and `make replay` remain **Phase 4**: they drive the scenario generator, which does not
exist yet.

---

## 3. Committed files

**None.** No dataset file, sample extract, or model artifact is committed. Every row is generated on
demand from the seed above, which is why there is no file list and no checksum table here.

`.gitignore` excludes generated bulk data and model artifacts. If a small reviewed sample is ever
committed for documentation, it is listed here with its size and SHA-256 before it is committed,
not after.

---

## 4. Limitations of synthetic data

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
it was measured on. See [`../../PROJECT_STATE.md`](../../PROJECT_STATE.md).

---

## 5. Removing or reproducing the data

Reproduce: set `SENTINELFLOW_SEED` and `SENTINELFLOW_SEED_PROFILE` to the values in the manifest and
run the loader against an empty database. The manifest checksum from the new run must match the old
one; if it does not, the generator version changed and the manifest says which.

Remove: `make reset-demo` drops the local stack's volumes, which is the only place generated data
ever exists. Nothing to purge from the repository, because nothing was ever committed.
