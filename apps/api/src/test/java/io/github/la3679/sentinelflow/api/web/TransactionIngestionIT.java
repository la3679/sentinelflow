/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import io.github.la3679.sentinelflow.api.support.TestCredentials;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Ingestion over HTTP, against the real schema.
 *
 * <p>The behaviour worth testing here is not the happy path. It is what happens on the second
 * request: a retry must return the original result rather than creating a second transaction, a
 * reused key with a different payload must be refused rather than silently answered, and two
 * concurrent submissions of the same key must produce exactly one transaction. That last one is the
 * only test in this file that could not be written against a mock, and it is the one that matters
 * most — the guarantee lives in a unique constraint, not in the code that looks the key up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionIngestionIT extends AbstractPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private RestTestClient client;
    private String accountReference;
    private String merchantReference;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        SchemaFixtures fixtures = new SchemaFixtures(jdbc);
        UUID customerId = fixtures.insertCustomer();
        UUID accountId = fixtures.insertAccount(customerId);
        UUID merchantId = fixtures.insertMerchant();
        accountReference =
                jdbc.queryForObject("SELECT account_reference FROM accounts WHERE id = ?", String.class, accountId);
        merchantReference =
                jdbc.queryForObject("SELECT merchant_reference FROM merchants WHERE id = ?", String.class, merchantId);
    }

    private Map<String, Object> body(String idempotencyKey) {
        return body(idempotencyKey, "1249.99");
    }

    private Map<String, Object> body(String idempotencyKey, String amount) {
        return Map.of(
                "idempotencyKey",
                idempotencyKey,
                "accountReference",
                accountReference,
                "merchantReference",
                merchantReference,
                "type",
                "PURCHASE",
                "channel",
                "CARD_NOT_PRESENT",
                "amount",
                Map.of("value", amount, "currency", "GBP"),
                "originCountry",
                "GB",
                "occurredAt",
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
    }

    /** Every submission carries the ingestion credential ADR-0017 §1 requires. */
    private RestTestClient.RequestBodySpec post() {
        return client.post()
                .uri("/api/v1/transactions")
                .header(ApiHeaders.API_KEY, TestCredentials.INGEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON);
    }

    /** Pulls one string field out of a response body, so a test can compare fields rather than bytes. */
    private static String fieldIn(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("%s is present in %s", field, json).isTrue();
        return matcher.group(1);
    }

    @Test
    @DisplayName("a valid transaction is accepted with 202 and a reference")
    void acceptsAValidTransaction() {
        String key = "ingest-" + SchemaFixtures.next6();

        String responseBody = post().body(body(key))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // 202, not 201: the transaction is durable, its assessment is not,
        // because scoring is asynchronous.
        assertThat(responseBody).contains("\"status\":\"ACCEPTED\"").containsPattern("TXN-[0-9]{6}");

        Integer stored =
                jdbc.queryForObject("SELECT count(*) FROM transactions WHERE idempotency_key = ?", Integer.class, key);
        assertThat(stored).isEqualTo(1);
    }

    @Test
    @DisplayName("the transaction and its outbox event are written together")
    void writesTheOutboxEventInTheSameTransaction() {
        String key = "outbox-" + SchemaFixtures.next6();
        post().body(body(key)).exchange().expectStatus().isAccepted();

        UUID transactionId =
                jdbc.queryForObject("SELECT id FROM transactions WHERE idempotency_key = ?", UUID.class, key);

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_id, schema_version, partition_key,
                       status, attempt_count, published_at, payload::text AS payload
                FROM outbox_events WHERE aggregate_id = ?
                """, transactionId);

        // The contract's spelling, not Java's - a consumer matches this against
        // the envelope it received.
        assertThat(event).containsEntry("event_type", "transaction.created");
        assertThat(event).containsEntry("aggregate_type", "transaction");
        assertThat(event).containsEntry("schema_version", 1);
        assertThat(event).containsEntry("status", "PENDING");
        assertThat(event).containsEntry("attempt_count", 0);
        assertThat(event.get("published_at")).isNull();
        // Keyed by account, not transaction (ADR-0006): velocity rules need one
        // account's events in order, and Kafka orders only within a partition.
        assertThat(event).containsEntry("partition_key", accountReference);

        // Read out of jsonb rather than matched against the rendered text.
        // PostgreSQL renders jsonb in its own key order and with its own
        // spacing, so a string comparison asserts the storage format rather
        // than the payload - which is what the first version of this test did,
        // and it failed for that reason rather than for a real one.
        Map<String, Object> payload = jdbc.queryForMap("""
                SELECT payload ->> 'accountReference'     AS account_reference,
                       payload ->> 'merchantReference'    AS merchant_reference,
                       payload ->> 'merchantCategoryCode' AS category_code,
                       payload ->> 'idempotencyKey'       AS idempotency_key,
                       payload ->> 'ingestionSource'      AS ingestion_source,
                       payload #>> '{amount,value}'       AS amount_value,
                       payload #>> '{amount,currency}'    AS amount_currency,
                       jsonb_typeof(payload -> 'amount' -> 'value') AS amount_value_type,
                       jsonb_typeof(payload -> 'deviceReference')   AS device_type
                FROM outbox_events WHERE aggregate_id = ?
                """, transactionId);

        assertThat(payload).containsEntry("account_reference", accountReference);
        assertThat(payload).containsEntry("merchant_reference", merchantReference);
        assertThat(payload).containsEntry("category_code", "5411");
        assertThat(payload).containsEntry("idempotency_key", key);
        assertThat(payload).containsEntry("ingestion_source", "API");

        // Money as a string, never a JSON number (ADR-0007) - asserted on the
        // JSON type rather than on the rendered text, because the type is the
        // thing that decides whether JSON.parse rounds it.
        assertThat(payload).containsEntry("amount_value_type", "string");
        assertThat(payload).containsEntry("amount_value", "1249.9900");
        assertThat(payload).containsEntry("amount_currency", "GBP");

        // Null rather than absent, so a consumer never has to distinguish
        // "this channel has no device" from "the producer forgot the field".
        assertThat(payload).containsEntry("device_type", "null");
    }

    @Test
    @DisplayName("resubmitting the same key returns the original result and creates nothing")
    void retryReturnsTheOriginalResult() {
        String key = "retry-" + SchemaFixtures.next6();
        Map<String, Object> request = body(key);

        String first = post().body(request)
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // 200, not 202: the status code is what tells a caller it is holding a
        // replay rather than a fresh acceptance.
        String second = post().body(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // Same transaction, which is the guarantee. The correlation identifier
        // deliberately differs: it identifies the request, and this is a
        // different request. A replay echoing the original request's identifier
        // would send whoever reads the logs to the wrong call.
        assertThat(fieldIn(second, "transactionId")).isEqualTo(fieldIn(first, "transactionId"));
        assertThat(fieldIn(second, "transactionReference")).isEqualTo(fieldIn(first, "transactionReference"));
        assertThat(fieldIn(second, "correlationId")).isNotEqualTo(fieldIn(first, "correlationId"));
        assertThat(fieldIn(second, "status")).isEqualTo("ACCEPTED");

        Integer transactions =
                jdbc.queryForObject("SELECT count(*) FROM transactions WHERE idempotency_key = ?", Integer.class, key);
        assertThat(transactions).isEqualTo(1);

        // And no second event. A duplicate outbox row would be published,
        // consumed, and scored a second time.
        Integer events = jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events o
                JOIN transactions t ON t.id = o.aggregate_id
                WHERE t.idempotency_key = ?
                """, Integer.class, key);
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different payload is a 409, not a silent replay")
    void reusedKeyWithADifferentPayloadIsRefused() {
        String key = "conflict-" + SchemaFixtures.next6();
        post().body(body(key, "10.00")).exchange().expectStatus().isAccepted();

        String problem = post().body(body(key, "9999.00"))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // Answering 200 here would leave the caller believing a transaction it
        // never submitted had been recorded, and would hide a broken key
        // generator indefinitely.
        assertThat(problem).contains("idempotency-conflict").contains("\"status\":409");
        assertThat(problem).doesNotContain("Exception").doesNotContain("io.github");
    }

    @Test
    @DisplayName("all three ingestion outcomes are counted, and none of them by account")
    void countsEveryIngestionOutcome() {
        double createdBefore = ingested("created");
        double replayedBefore = ingested("replayed");
        double conflictBefore = ingested("conflict");

        String key = "counted-" + SchemaFixtures.next6();
        // One body across the first two requests. occurredAt is part of the
        // payload comparison, so a fresh Instant.now() per call makes the second
        // a genuinely different submission and the replay becomes a conflict -
        // which is correct behaviour and the wrong thing to be counting here.
        Map<String, Object> submission = body(key, "12.00");
        Map<String, Object> different = new HashMap<>(submission);
        different.put("amount", Map.of("value", "77.00", "currency", "GBP"));

        post().body(submission).exchange().expectStatus().isAccepted();
        post().body(submission).exchange().expectStatus().isOk();
        post().body(different).exchange().expectStatus().isEqualTo(409);

        assertThat(ingested("created") - createdBefore).isEqualTo(1);
        assertThat(ingested("replayed") - replayedBefore)
                .as("a retry storm has to be visible as a replay rate rather than as throughput "
                        + "that looks flat while the database works")
                .isEqualTo(1);
        assertThat(ingested("conflict") - conflictBefore)
                .as("a caller whose key generator has broken should show up here rather than in a " + "support ticket")
                .isEqualTo(1);

        assertThat(meters.getMeters())
                .filteredOn(meter -> meter.getId().getName().equals("sentinelflow.transactions.ingested"))
                .isNotEmpty()
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .as("ADR-0016 section 2: an account or a merchant here would be one series "
                                + "per party, holding an identifier in a label")
                        // `application` is the common tag every meter in this
                        // service carries, from management.metrics.tags - one
                        // constant value, and the thing that tells two services'
                        // series apart in one Prometheus.
                        .allSatisfy(tag -> assertThat(tag.getKey()).isIn("source", "outcome", "application")));
    }

    private double ingested(String outcome) {
        Counter counter = meters.find("sentinelflow.transactions.ingested")
                .tag("source", "API")
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("a formatting difference in the amount is a retry, not a conflict")
    void scaleDifferenceIsStillTheSameSubmission() {
        String key = "scale-" + SchemaFixtures.next6();
        // One timestamp across both requests. occurredAt is part of the payload
        // comparison, so a fresh Instant.now() per call makes these two genuinely
        // different submissions and the conflict would be correct - which is what
        // the first version of this test actually demonstrated.
        Map<String, Object> first = body(key, "10.50");
        Map<String, Object> second = new HashMap<>(first);
        second.put("amount", Map.of("value", "10.5", "currency", "GBP"));

        post().body(first).exchange().expectStatus().isAccepted();

        // 10.50 and 10.5 are the same amount. Treating a client's harmless
        // formatting difference as a conflict would refuse a legitimate retry.
        post().body(second).exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("two concurrent submissions of one key produce exactly one transaction")
    void concurrentSubmissionsCreateOneTransaction() throws Exception {
        String key = "race-" + SchemaFixtures.next6();
        Map<String, Object> request = body(key);
        int callers = 8;

        // The lookup in the service cannot make this safe - a check-then-insert
        // has a window by construction, and all eight of these pass the check.
        // What makes it safe is transactions_idempotency_unique, and this is the
        // test that proves the code loses that race gracefully rather than
        // returning a 500.
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            List<Callable<Integer>> submissions = java.util.Collections.nCopies(callers, () -> post().body(request)
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value());

            List<Future<Integer>> results = pool.invokeAll(submissions);
            for (Future<Integer> result : results) {
                // Every caller gets an answer it can act on: accepted, or
                // already accepted. None gets a 500.
                assertThat(result.get()).isIn(200, 202);
            }
        }

        Integer transactions =
                jdbc.queryForObject("SELECT count(*) FROM transactions WHERE idempotency_key = ?", Integer.class, key);
        assertThat(transactions).isEqualTo(1);

        Integer events = jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events o
                JOIN transactions t ON t.id = o.aggregate_id
                WHERE t.idempotency_key = ?
                """, Integer.class, key);
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown account reference is a 422 naming the field, not a 500")
    void unknownAccountIsRejected() {
        Map<String, Object> request = new HashMap<>(body("unknown-" + SchemaFixtures.next6()));
        request.put("accountReference", "ACC-999999");

        String problem = post().body(request)
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // ACC-999999 is a well-formed reference that names nothing. Answering
        // "malformed" would send the caller looking for a typo that is not there.
        assertThat(problem).contains("unknown-reference").contains("accountReference");
    }

    @Test
    @DisplayName("a malformed field is a 422 that names it, and nothing else")
    void validationFailureNamesTheField() {
        Map<String, Object> request = new HashMap<>(body("valid-" + SchemaFixtures.next6()));
        request.put("originCountry", "united kingdom");

        String problem = post().body(request)
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem)
                .contains("validation-failed")
                .contains("originCountry")
                .contains("ISO 3166-1");
        assertThat(problem)
                .doesNotContain("Exception")
                .doesNotContain("io.github")
                .doesNotContain("\tat ");
    }

    @Test
    @DisplayName("an amount sent as a JSON number is refused")
    void amountMustBeAString() {
        // ADR-0007: a JSON number is rounded by JSON.parse before a consumer's
        // code sees it, so the value is wrong before anyone can defend it.
        //
        // Jackson would coerce this into the String field and the money pattern
        // would then match, so the rule would have been broken by the parser
        // before any of this project's code ran. JsonCoercionConfiguration is
        // what stops it.
        String request = """
                {"idempotencyKey":"numeric-money-1","accountReference":"%s","merchantReference":"%s",
                 "type":"PURCHASE","channel":"CARD_NOT_PRESENT",
                 "amount":{"value":1249.99,"currency":"GBP"},
                 "originCountry":"GB","occurredAt":"2026-08-26T10:00:00Z"}
                """.formatted(accountReference, merchantReference);

        // 400, not 422. A field of the wrong JSON type is a malformed body -
        // deserialisation never completes, so there is no object to validate.
        // 422 is for a body that parsed and then failed a rule.
        post().body(request)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("an unknown field is rejected rather than ignored")
    void unknownFieldIsRejected() {
        // The contract says additionalProperties: false. A silently ignored
        // typo means a transaction recorded with a default the caller never
        // chose.
        String request = """
                {"idempotencyKey":"unknown-field-1","accountReference":"%s","merchantReference":"%s",
                 "type":"PURCHASE","channel":"CARD_NOT_PRESENT",
                 "amount":{"value":"10.00","currency":"GBP"},
                 "originCountry":"GB","occurredAt":"2026-08-26T10:00:00Z","riskBand":"LOW"}
                """.formatted(accountReference, merchantReference);

        post().body(request).exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("the correlation identifier is echoed, and a supplied one is honoured")
    void correlationIdentifierIsEchoed() {
        UUID supplied = UUID.fromString("01936b2a-7c4f-7000-8000-1a2b3c4d5e6f");

        String responseBody = post().header(CorrelationIdFilter.HEADER, supplied.toString())
                .body(body("corr-" + SchemaFixtures.next6()))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectHeader()
                .valueEquals(CorrelationIdFilter.HEADER, supplied.toString())
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(responseBody).contains(supplied.toString());
    }

    @Test
    @DisplayName("a correlation header that is not a UUID is replaced, not echoed")
    void malformedCorrelationIdentifierIsReplaced() {
        // This value reaches the logs and a response header. Echoing arbitrary
        // client text into either is a log-forging vector, and the check that
        // prevents it is the same one that keeps the identifier meaningful.
        //
        // Not a CRLF payload: the HTTP client refuses to send one, so a test
        // written that way asserts the client's own validation rather than this
        // filter's. A plain malformed value is what actually reaches the server.
        String hostile = "not-a-uuid and some trailing junk";

        String echoed = post().header(CorrelationIdFilter.HEADER, hostile)
                .body(body("hostile-" + SchemaFixtures.next6()))
                .exchange()
                .expectStatus()
                .isAccepted()
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst(CorrelationIdFilter.HEADER);

        assertThat(echoed).isNotNull();
        assertThat(UUID.fromString(echoed)).isNotNull();
        assertThat(echoed).doesNotContain("junk");
    }
}
