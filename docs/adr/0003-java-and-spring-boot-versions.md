# ADR-0003 — Java 25 LTS and Spring Boot 4.1.1

- **Status:** Accepted
- **Date:** 2026-08-25
- **Research:** R-2026-08-25-02, R-2026-08-25-03, R-2026-08-25-04 in
  [`docs/research/RESEARCH_LOG.md`](../research/RESEARCH_LOG.md)

## Context

`apps/api` needs a Java runtime and a Spring Boot version that are stable, mutually supported, and
supported by every downstream dependency the project needs — Kafka, Flyway, PostgreSQL,
Testcontainers, Micrometer, OpenTelemetry, Resilience4j, and springdoc.

Spring Boot **4.1.1** is the current stable release; `4.2.0-M1` is a milestone and is excluded by
the research rule against pre-release core dependencies. Spring Boot 4.1.1 supports **Java 17
through Java 26**. The LTS choices inside that range are 17, 21, and 25.

The reference machine had JDK 17, 19, 21, and 11 installed — but no JDK 25.

## Decision

**Target Java 25 (LTS), release 25, on Spring Boot 4.1.1.**

Dependency versions are inherited from `spring-boot-starter-parent:4.1.1` wherever the BOM manages
them. Only four artifacts carry an explicit version, because the BOM does not manage them:

| Artifact                                            | Version | Why explicit                                                           |
| --------------------------------------------------- | ------- | ---------------------------------------------------------------------- |
| `io.github.resilience4j:resilience4j-spring-boot4`  | 2.4.0   | Spring Boot 4 variant; the `-spring-boot3` artifact is the Boot 3 line |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 3.1.0   | springdoc 3.x is the Spring Framework 7 line                           |
| `org.jacoco:jacoco-maven-plugin`                    | 0.8.15  | coverage gate                                                          |
| `com.diffplug.spotless:spotless-maven-plugin`       | 3.10.0  | formatting gate                                                        |

Java 25 was provisioned locally from the **official Adoptium `.zip`** (build `jdk-25.0.4.1+1`),
SHA-256 verified against the Adoptium API before extraction, and installed to the per-user
`~/.jdks` directory. A machine-wide MSI install was deliberately avoided: it requires elevation,
and `winget --scope user` cannot install an MSI (it fails with exit code 16).

## Alternatives considered

- **Java 21 LTS** — already installed locally and equally well supported. Rejected because it
  offers no advantage once 25 is provisioned, and 25 has the longer support runway.
- **Java 17** — the Spring Boot 4.1 floor. Rejected: oldest supported LTS, no upside.
- **Spring Boot 4.2.0-M1** — rejected as a milestone build.
- **Gradle instead of Maven** — no ADR-worthy benefit identified. The Maven Wrapper is committed
  and `starter-parent` BOM inheritance is the simplest reproducible option here.

## Consequences

**Positive** — a single parent-version bump upgrades the whole managed dependency set.
Testcontainers 2.0.5, Kafka 4.2.1, Flyway 12.4.0, PostgreSQL driver 42.7.13, and JUnit 6.0.3 are
guaranteed mutually compatible because Spring Boot ships them as a set.

**Negative** — the reference machine's default `JAVA_HOME` still points at JDK 17, so
`docs/development/LOCAL_DEVELOPMENT.md` must document JDK 25 selection for both PowerShell and
Bash/WSL. Contributors need a JDK 25 they may not already have.

**Important** — because the BOM pins **Testcontainers 2.x**, integration tests must use the
Testcontainers 2.x API. The deprecated 1.x `KafkaContainer` patterns found in most online examples
do not apply here.
