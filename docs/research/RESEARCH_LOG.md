# SentinelFlow Research Log

Append-only record of the research gate required before selecting versions, datasets, or
architecture. Every entry records the date, the authoritative source consulted, the decision
taken, and the impact on the implementation.

Rules applied throughout:

- Primary sources only for version and security decisions (official documentation, project
  release metadata, Maven Central / PyPI / Docker Hub registries, standards bodies).
- No release candidate, milestone, beta, snapshot, or nightly version for a core dependency
  when a supported stable release exists.
- "Latest" is never selected blindly; the newest **mutually supported** stable combination is
  selected and justified.
- Every consequential choice is promoted to an ADR under [`docs/adr/`](../adr/).

---

## R-2026-08-25-01 — Local toolchain inventory (reference environment)

**Date (UTC):** 2026-08-25
**Source:** direct inspection of the development machine.

| Tool               | Version found                                                             |
| ------------------ | ------------------------------------------------------------------------- |
| OS                 | Windows 11 Pro 10.0.26200                                                 |
| git                | 2.42.0.windows.2                                                          |
| GitHub CLI         | 2.95.0 (authenticated, scopes `gist, read:org, repo, workflow`)           |
| Java (JAVA_HOME)   | Temurin 17.0.11                                                           |
| Other JDKs present | Corretto 21.0.4, Corretto 19.0.2, MS 11.0.29                              |
| Node.js            | 22.19.0                                                                   |
| npm                | 11.6.0                                                                    |
| Python             | 3.11.9                                                                    |
| uv                 | 0.12.2                                                                    |
| Docker Engine      | 29.5.3 (Linux containers, 20 CPU, 25.0 GB memory available to the daemon) |
| Docker Compose     | v5.1.4                                                                    |
| Maven              | not installed (Maven Wrapper will be committed)                           |

**Decision:** the reference environment is capable of running the full Compose stack without a
GPU. Maven is intentionally _not_ installed system-wide; the project commits `mvnw`/`mvnw.cmd`
so a clean clone is reproducible.

**Impact:** two gaps identified and closed below — no JDK 25 present (R-…-03) and Node.js 22 is
past end-of-life (R-…-05).

---

## R-2026-08-25-02 — Spring Boot stable release and supported Java range

**Date (UTC):** 2026-08-25
**Sources:**

- <https://spring.io/projects/spring-boot> — current version displayed: 4.1.1
- <https://docs.spring.io/spring-boot/system-requirements.html> — system requirements for 4.1.1
- `https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml`
  — full published version list

**Findings:**

- Latest **stable** Spring Boot is **4.1.1**. The registry also publishes `4.2.0-M1`, which is a
  milestone and is therefore excluded by the research rules.
- Spring Boot 4.1.1 system requirements: **Java 17 minimum, Java 26 maximum**; Spring Framework
  7.0.9+; Maven 3.6.3+; Gradle 8.14+/9.x; Servlet 6.1+ containers (Tomcat 11.0.x, Jetty 12.1.x).

**Decision:** adopt **Spring Boot 4.1.1**.

**Impact:** the whole supported LTS range (17, 21, 25) is open; Java version is chosen in
R-…-03 on other grounds. Recorded in [ADR-0003](../adr/0003-java-and-spring-boot-versions.md).

---

## R-2026-08-25-03 — Java LTS selection and local JDK provisioning

**Date (UTC):** 2026-08-25
**Sources:**

- <https://api.adoptium.net/v3/assets/latest/25/hotspot?os=windows&architecture=x64&image_type=jdk>
- Spring Boot 4.1.1 system requirements (R-…-02)

**Findings:**

- **Java 25 is an LTS release** and sits inside Spring Boot 4.1.1's supported range (17–26).
- No JDK 25 was present on the reference machine. `winget install EclipseAdoptium.Temurin.25.JDK
--scope user` fails with exit code 16 ("No applicable installer found") because the Temurin
  Windows package is an MSI, which only supports machine scope and therefore requires elevation.
- Adoptium publishes a **redistributable `.zip`** of the same build with a SHA-256 checksum
  served from the Adoptium API.

**Decision:** target **Java 25 (LTS)**. Provision the local JDK from the official Adoptium zip
into the per-user `~/.jdks` directory (the same convention JetBrains tooling uses) rather than
performing an elevated machine-wide install.

Provisioned build, checksum-verified before extraction:

| Field            | Value                                                                           |
| ---------------- | ------------------------------------------------------------------------------- |
| Distribution     | Eclipse Temurin (HotSpot)                                                       |
| Build            | `jdk-25.0.4.1+1`                                                                |
| Reported version | `openjdk 25.0.4.1 2026-08-18 LTS`                                               |
| Archive          | `OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip`                             |
| Expected SHA-256 | `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283`              |
| Verified SHA-256 | `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283` (match)      |
| Install location | `%USERPROFILE%\.jdks\jdk-25.0.4.1+1` (user scope, no elevation, no PATH change) |

**Impact:** `apps/api` targets release 25. `docs/development/LOCAL_DEVELOPMENT.md` must document
JDK 25 setup for both PowerShell and Bash/WSL, because the machine default `JAVA_HOME` still
points at JDK 17. CI and the API container image both pin a JDK 25 base.

---

## R-2026-08-25-04 — Backend dependency versions via the Spring Boot BOM

**Date (UTC):** 2026-08-25
**Source:** `https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom`
(authoritative managed-version list), plus `maven-metadata.xml` on Maven Central for artifacts
the BOM does not manage.

**Managed by the Spring Boot 4.1.1 BOM — these are _not_ overridden:**

| Dependency              | Managed version |
| ----------------------- | --------------- |
| Spring Framework        | 7.0.9           |
| Spring for Apache Kafka | 4.1.1           |
| Apache Kafka clients    | 4.2.1           |
| Flyway                  | 12.4.0          |
| PostgreSQL JDBC driver  | 42.7.13         |
| Testcontainers          | 2.0.5           |
| JUnit Jupiter           | 6.0.3           |
| Micrometer              | 1.17.1          |
| OpenTelemetry           | 1.62.0          |

**Not managed by the BOM — pinned explicitly:**

| Dependency                                          | Pinned version | Note                                                                                                |
| --------------------------------------------------- | -------------- | --------------------------------------------------------------------------------------------------- |
| `io.github.resilience4j:resilience4j-spring-boot4`  | 2.4.0          | Spring Boot 4 variant; the `-spring-boot3` artifact is the Boot 3 line and is deliberately not used |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 3.1.0          | springdoc 3.x is the Spring Boot 4 / Spring Framework 7 line                                        |
| `org.jacoco:jacoco-maven-plugin`                    | 0.8.15         | coverage gate                                                                                       |
| `com.diffplug.spotless:spotless-maven-plugin`       | 3.10.0         | formatting gate                                                                                     |

**Decision:** inherit from `spring-boot-starter-parent:4.1.1` and let the BOM govern every
dependency it manages. Only the four artifacts above carry an explicit version.

**Impact:** removes a whole class of version-drift bugs and makes dependency upgrades a single
parent-version bump. Testcontainers **2.0.5** (not the 1.x line) is what the BOM aligns with, so
integration tests must be written against the Testcontainers 2.x API — the deprecated
`KafkaContainer` 1.x usage patterns are not applicable.

---

## R-2026-08-25-05 — Node.js LTS

**Date (UTC):** 2026-08-25
**Source:** <https://nodejs.org/en/about/previous-releases>

**Findings:**

| Line   | Codename    | Status         | End date   |
| ------ | ----------- | -------------- | ---------- |
| 26     | —           | Current        | 2026-08-05 |
| **24** | **Krypton** | **Active LTS** | 2026-08-03 |
| 22     | Jod         | LTS (ended)    | 2026-07-28 |
| 20     | Iron        | End-of-life    | 2026-03-24 |

**Decision:** target **Node.js 24 (Active LTS)** in `.nvmrc`, the `engines` field, CI, and the
web container image.

**Impact:** the reference machine currently runs Node 22.19.0, whose LTS window closed on
2026-07-28. This is recorded as a known environment gap; local Node 24 is required before the
frontend gate can be declared verified. Documented in `docs/development/LOCAL_DEVELOPMENT.md`.

---

## R-2026-08-25-06 — Python runtime and scientific stack compatibility

**Date (UTC):** 2026-08-25
**Source:** PyPI JSON API (`https://pypi.org/pypi/<package>/json`) — `requires_python` and the
`Programming Language :: Python ::` classifiers for each selected package.

| Package           | Version | `requires_python` | Declared Python support |
| ----------------- | ------- | ----------------- | ----------------------- |
| fastapi           | 0.141.1 | >=3.10            | 3.10–3.14               |
| uvicorn           | 0.52.4  | >=3.10            | 3.10–3.14               |
| pydantic          | 2.13.4  | >=3.9             | 3.9–3.14                |
| pydantic-settings | 2.15.0  | >=3.10            | 3.10–3.14               |
| scikit-learn      | 1.9.0   | **>=3.11**        | 3.11–3.14               |
| numpy             | 2.5.2   | **>=3.12**        | 3.12–3.15               |
| pandas            | 3.0.5   | >=3.11            | 3.11–3.14               |
| joblib            | 1.5.3   | >=3.9             | **3.9–3.13**            |
| pytest            | 9.1.1   | >=3.10            | 3.10–3.15               |
| pytest-cov        | 7.1.0   | >=3.9             | 3.9–3.14                |
| prometheus-client | 0.26.0  | >=3.9             | 3.9–3.14                |
| opentelemetry-sdk | 1.44.0  | >=3.10            | 3.10–3.14               |
| structlog         | 26.1.0  | >=3.10            | 3.10–3.15               |
| httpx             | 0.28.1  | >=3.8             | 3.8–3.12                |
| ruff              | 0.16.4  | >=3.7             | 3.7–3.14                |
| mypy              | 2.3.1   | >=3.10            | 3.10–3.15               |

**Findings:** the binding constraints are **numpy 2.5.2 (>= 3.12 floor)** and
**joblib 1.5.3 (3.13 classifier ceiling)**. The intersection where every selected package
declares support is therefore exactly **Python 3.13**.

**Decision:** target **Python 3.13** for `apps/scoring`, managed by `uv` with a committed
`uv.lock`. Python 3.14 is deliberately _not_ selected: joblib — which the model-serialization
path depends on — does not yet declare 3.14 support, and model serialization is not a place to
take an unsupported-runtime risk.

**Impact:** the reference machine has Python 3.11.9 system-wide, which is below numpy's floor.
`uv` provisions and pins 3.13 for the project, so no system Python change is required. Recorded
in [ADR-0004](../adr/0004-python-runtime-and-model-stack.md).

---

## R-2026-08-25-07 — Infrastructure image versions

**Date (UTC):** 2026-08-25
**Source:** Docker Hub registry tag listings (`https://hub.docker.com/v2/repositories/<repo>/tags`).

| Component               | Selected tag                                   | Rationale                                                                                                                                                                                                                    |
| ----------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PostgreSQL              | `postgres:18.6-alpine`                         | newest stable major (18.x); alpine keeps the local stack small                                                                                                                                                               |
| Apache Kafka            | `apache/kafka:4.2.1`                           | **exact match** for the Kafka client version managed by the Spring Boot 4.1.1 BOM; official image, KRaft mode built in, no ZooKeeper. `4.3.1` exists but broker/client alignment is the conservative choice for a demo stack |
| Prometheus              | `prom/prometheus:v3.14.0`                      | current stable                                                                                                                                                                                                               |
| Grafana                 | `grafana/grafana:13.2.0`                       | current stable                                                                                                                                                                                                               |
| OpenTelemetry Collector | `otel/opentelemetry-collector-contrib:0.159.0` | current stable contrib distribution                                                                                                                                                                                          |

**Decision:** pin all infrastructure images to explicit tags; no `:latest` anywhere.
**Impact:** `compose.yaml` and CI use identical pinned tags so local and CI behaviour match.
Kafka runs in **KRaft mode** — ZooKeeper is not part of the stack.

---

## R-2026-08-25-08 — Lovable ↔ GitHub synchronization behaviour

**Date (UTC):** 2026-08-25
**Source:** <https://docs.lovable.dev/integrations/github>

**Findings:**

- Connecting a Lovable project **creates a new GitHub repository**. Lovable **cannot import an
  existing repository**. This is why the repository must be created _by Lovable_, not by `gh`.
- Sync is **two-way** but **one branch at a time** — by default the repository default branch.
  A branch picker allows switching; new branches are created from the currently active branch.
- **Renaming** the repository is tracked automatically and is safe. **Transferring** it to
  another account breaks sync and needs support intervention. **Deleting** it breaks sync.
- **Branch protection:** if the synced branch rejects Lovable's push, Lovable redirects the
  change to a backup branch rather than failing outright.

**Decision:**

1. Lovable creates `la3679/sentinelflow`; the repository is never renamed, transferred, or
   deleted.
2. Strict `main` protection is enabled **after** the initial Lovable phase, and Lovable is
   pointed at a dedicated `design/lovable-*` branch for subsequent design sessions so that
   protection on `main` cannot silently divert its pushes.
3. Lovable and Claude never edit the same branch concurrently.

**Impact:** drives the choreography in §15 and is recorded in
[ADR-0001](../adr/0001-lovable-first-repository-creation.md).

---

## R-2026-08-25-09 — Lovable's current generation stack is TanStack Start

**Date (UTC):** 2026-08-25
**Sources:** the generated project's `package.json`, `vite.config.ts`, and `tsconfig.json`;
Lovable GitHub integration documentation (R-2026-08-25-08).

**Findings:** **Lovable currently generates new applications with TanStack Start.** This is
Lovable's present default generation stack for new projects, not a template chosen by mistake and
not something a differently worded prompt would change — the project-creation API exposes no
tech-stack selector, and the generated app is wired to Lovable's own
`@lovable.dev/vite-tanstack-config` package, which supplies the TanStack Start, Nitro, Tailwind,
path-alias, and env-injection Vite plugins as one managed unit.

Verified versions in the generated project:

| Concern         | Generated                                                                              | Specification nominated           |
| --------------- | -------------------------------------------------------------------------------------- | --------------------------------- |
| Build tool      | Vite 8.1.5                                                                             | Vite — match                      |
| Framework       | TanStack Start 1.168.32, React 19.2                                                    | plain React SPA                   |
| Router          | TanStack Router 1.170.18 (file-based)                                                  | React Router                      |
| Components      | Tailwind CSS 4.2.1 + shadcn/ui (Radix)                                                 | Material UI                       |
| Data access     | Redux Toolkit 2.12 + RTK Query                                                         | RTK Query — match                 |
| Charts          | Recharts 2.15.4                                                                        | Recharts — match                  |
| Forms           | React Hook Form 7.71 + Zod 3.24                                                        | RHF + Zod — match                 |
| Package manager | Bun, single `bun.lock`                                                                 | one manager, one lockfile — match |
| TypeScript      | 5.8.3, `strict: true` plus `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes` | strict TypeScript — match         |

Six of nine concerns match the specification exactly. The three that differ are properties of
Lovable's generation stack.

**Decision:** adopt the generated foundation. Radix primitives supply the focus management,
keyboard interaction, and ARIA semantics the WCAG 2.2 AA target requires, are MIT licensed, and
have no paid tier — satisfying the "free/community components only" constraint that motivated the
Material UI nomination. TanStack Router satisfies the client-side, deep-linkable routing
requirement behind the React Router nomination.

One correction is applied: the console is configured to render **client-side**, shipping static
assets rather than running the generated Nitro SSR server, so that **Spring Boot remains the sole
application backend**.

**Impact:** recorded in [ADR-0009](../adr/0009-frontend-component-library.md). Lovable generated
no test tooling, so Vitest, React Testing Library, Playwright, and axe are added in Phase 1.

---

## R-2026-08-25-10 — Data source licensing

**Date (UTC):** 2026-08-25
**Sources:** the three repositories named in §13 of the build prompt.

| Source                                                                                            | License                                       | Use in SentinelFlow                                                                                                                                                                                                                      |
| ------------------------------------------------------------------------------------------------- | --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| IBM AMLSim — <https://github.com/IBM/AMLSim>                                                      | Apache-2.0                                    | **Optional CSV import adapter only.** Its legacy Java/Python dependencies must not become SentinelFlow runtime dependencies. Not required for v1.                                                                                        |
| SantanderAI `gen-fraud-graph` — <https://github.com/SantanderAI/gen-fraud-graph>                  | Apache-2.0                                    | Researched as a possible future graph data source. **Out of scope for v1** — no graph database or paid embeddings in the core path.                                                                                                      |
| Fraud Detection Handbook — <https://github.com/Fraud-Detection-Handbook/fraud-detection-handbook> | Code **GPLv3**; prose/images **CC BY-SA 4.0** | **Conceptual research only** — for imbalanced-evaluation methodology. **No code, prose, or images copied**, because GPLv3 is incompatible with this project's Apache-2.0 licensing. Cited where its concepts informed evaluation design. |

**Decision:** the **default and only v1 data source is SentinelFlow's own original,
deterministic, seed-based synthetic generator**. No third-party dataset is redistributed.

**Impact:** removes all redistribution ambiguity. Details recorded in
`docs/data/DATA_PROVENANCE.md`. Project license is **Apache-2.0**.

---

## Open items to revalidate

- [ ] Security advisories affecting the pinned versions — run the dependency and CodeQL scans in
      Phase 8 and record findings here.
- [ ] Claude Code hook / status-line / memory schema — verify against the installed version
      before committing `.claude/` configuration (Phase 1).
- [ ] Node.js 24 present on the reference machine before the frontend gate is declared verified.
- [ ] Charting library licence re-check once the Recharts version is pinned by the lockfile.
