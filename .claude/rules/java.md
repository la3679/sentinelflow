# Java and Spring Boot rules — `apps/api`

Binding for every change under `apps/api`. Java 25 LTS, Spring Boot 4.1.1
(ADR-0003). Versions come from the Spring Boot BOM; do not pin one it manages.

## Layering

```text
web/          controllers, request and response DTOs, exception handling
service/      business rules, transactions, orchestration
domain/       entities, value objects, domain events, repository interfaces
persistence/  JPA entities, Spring Data repositories, mappers
messaging/    Kafka producers, consumers, outbox relay
config/       configuration properties and beans
```

- **A controller validates, delegates, and maps.** Never a business rule, never
  a repository call, never a Kafka publish.
- **A JPA entity never crosses the web boundary.** Map to a DTO. Exposing an
  entity leaks the schema into the API contract and makes a lazy association a
  serialization bug.
- **A service owns the transaction.** `@Transactional` goes on the service
  method, not on the controller and not on the repository.

## Money

`BigDecimal`, always, with an explicit currency code. Never `double`, never
`float`, never a floating-point intermediate in a calculation, and never
`BigDecimal.equals` for a value comparison — use `compareTo`.

The database column is `NUMERIC`. The API and event representations are decimal
strings, so a JavaScript consumer cannot silently round them.

## Errors

- Throw a domain-meaningful exception. Do not throw `RuntimeException`.
- Handle it once, at the boundary, in a `@RestControllerAdvice`.
- Never catch and ignore. Never log-and-rethrow the same exception at every
  layer — one log, at the place that decides what to do about it.
- The response body is RFC 9457 `application/problem+json` and never contains a
  stack trace, a SQL fragment, or an internal class name.

## Validation and boundaries

- Bean Validation on every inbound DTO. Validate at the boundary, not in a
  service that assumes the boundary already did it.
- Every list endpoint is paged, with a maximum page size the server enforces.
  An endpoint whose result grows with the dataset is a denial-of-service
  primitive.
- Idempotency keys on every mutating ingestion endpoint.

## Persistence

- Flyway owns the schema. `ddl-auto` is `validate`, never `update`.
- A migration is immutable once merged. Fix a mistake with a new migration.
- Every migration is tested against real PostgreSQL through Testcontainers.
  **H2 is not evidence that a PostgreSQL migration works.**

## Messaging

- The outbox is the only way a state change becomes an event. Never publish to
  Kafka inside the same code path that writes the row without the outbox — the
  two are not atomic.
- Consumers are idempotent. At-least-once delivery means a duplicate is normal,
  not exceptional.
- Every event carries the envelope defined in `contracts/asyncapi/`.

## Tests

- JUnit 5 and AssertJ. `@SpringBootTest` when the wiring is the thing under
  test; a plain unit test when it is not.
- Testcontainers for PostgreSQL and Kafka. A suite that mocks both away is not
  an integration test.
- Assert behaviour, not implementation. A test that only verifies a mock was
  called proves the code calls itself.

## Style

Spotless with palantir-java-format runs at `verify` and fails the build. Run
`./mvnw spotless:apply` rather than arguing with it.
