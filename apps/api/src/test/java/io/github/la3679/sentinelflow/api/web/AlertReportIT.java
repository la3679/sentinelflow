/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.security.TokenIssuer;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two reports, each over a window its own test owns entirely.
 *
 * <p><strong>Every test gets a window nobody else can be in — not another suite, and not
 * another test in this class.</strong> One container serves the whole fork, so a report over "now"
 * would count other suites' rows and no assertion about a total could be written. A window shared
 * across this class is the same mistake one level down: every test writes alerts, so an exact total
 * would count whatever the tests before it left behind. Each test is therefore given its own hour in
 * the past, and the hours are two apart so that the half-open test can read the adjacent window
 * without straying into anyone else's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertReportIT extends AbstractPostgresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The first hour this run may use. Distinct per run, so two forks never share one. */
    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z")
            .plus(Duration.ofHours(Math.floorMod(System.nanoTime(), 10_000L)))
            .truncatedTo(ChronoUnit.HOURS);

    /** Hands out one window per test. Two hours apart; see {@link #theWindowIsHalfOpen()}. */
    private static final AtomicInteger WINDOWS = new AtomicInteger();

    private Instant windowStart;
    private Instant windowEnd;

    @LocalServerPort
    private int port;

    @Autowired
    private TokenIssuer tokens;

    @Autowired
    private JdbcTemplate jdbc;

    private SchemaFixtures fixtures;
    private RestTestClient client;
    private String analystToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        windowStart = EPOCH.plus(Duration.ofHours(2L * WINDOWS.getAndIncrement()));
        windowEnd = windowStart.plus(Duration.ofHours(1));

        fixtures = new SchemaFixtures(jdbc);
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        UUID principal = fixtures.systemUserId();
        analystToken = tokens.issue(principal, List.of(RoleCode.ANALYST), Instant.now())
                .value();
        auditorToken = tokens.issue(principal, List.of(RoleCode.AUDITOR), Instant.now())
                .value();
    }

    // ----------------------------------------------------------------------- //
    // The summary
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the summary counts the window by status, priority and band")
    void summarisesTheWindow() {
        alertIn(windowStart, "HIGH", "NEW");
        alertIn(windowStart.plusSeconds(60), "URGENT", "NEW");
        UUID closed = alertIn(windowStart.plusSeconds(120), "HIGH", "NEW");
        close(closed);

        JsonNode summary = MAPPER.readTree(summary(analystToken, windowStart, windowEnd, 200));

        assertThat(summary.get("total").asInt()).isEqualTo(3);
        assertThat(summary.get("open").asInt()).isEqualTo(2);
        assertThat(summary.get("closed").asInt())
                .as("open is derived from closed_at rather than from a list of statuses, so adding "
                        + "a status cannot make this quietly wrong")
                .isEqualTo(1);
        assertThat(summary.get("byPriority").get("HIGH").asInt()).isEqualTo(2);
        assertThat(summary.get("byPriority").get("URGENT").asInt()).isEqualTo(1);
        assertThat(summary.get("byStatus").get("NEW").asInt()).isEqualTo(2);
        assertThat(summary.get("byStatus").get("CLOSED").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("every key is present, including the ones that are zero")
    void reportsZeroesRatherThanGaps() {
        alertIn(windowStart, "HIGH", "NEW");

        JsonNode summary = MAPPER.readTree(summary(analystToken, windowStart, windowEnd, 200));

        // A missing key and a zero are the same fact, and a client should not
        // have to know that. A chart with a gap where CRITICAL should be reads
        // as missing data rather than as none.
        assertThat(summary.get("byStatus").get("ESCALATED").asInt()).isZero();
        assertThat(summary.get("byPriority").get("LOW").asInt()).isZero();
        assertThat(summary.get("byBand").get("CRITICAL").asInt()).isZero();
    }

    @Test
    @DisplayName("the window is half-open, so two adjacent windows neither overlap nor lose a row")
    void theWindowIsHalfOpen() {
        alertIn(windowEnd, "HIGH", "NEW");

        JsonNode before = MAPPER.readTree(summary(analystToken, windowStart, windowEnd, 200));
        JsonNode after = MAPPER.readTree(summary(analystToken, windowEnd, windowEnd.plusSeconds(3600), 200));

        // The row sits exactly on the boundary. A closed range would count it
        // twice across two reports and an open one would lose it, and both
        // mistakes are invisible in the output.
        assertThat(before.get("total").asInt()).isZero();
        assertThat(after.get("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("an auditor can read a report and an anonymous caller cannot")
    void auditorsCanRead() {
        summary(auditorToken, windowStart, windowEnd, 200);

        client.get()
                .uri(summaryUri(windowStart, windowEnd))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("an inverted window is refused rather than answered with nothing")
    void refusesAnInvertedWindow() {
        // It matches no rows, so the honest-looking answer is an empty report -
        // which reads as "there were no alerts" and is the worst thing a report
        // can say when it is not true.
        JsonNode problem = MAPPER.readTree(summary(analystToken, windowEnd, windowStart, 422));

        assertThat(problem.get("detail").asString()).contains("must be after");
    }

    @Test
    @DisplayName("a window wider than the maximum is refused")
    void refusesAnUnboundedWindow() {
        summary(analystToken, windowStart.minus(Duration.ofDays(400)), windowEnd, 422);
    }

    // ----------------------------------------------------------------------- //
    // The export
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the export is a downloadable CSV with a header and one row per alert")
    void exportsCsv() {
        alertIn(windowStart, "HIGH", "NEW");
        alertIn(windowStart.plusSeconds(60), "URGENT", "NEW");

        String csv = client.get()
                .uri(exportUri(windowStart, windowEnd))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith("text/csv")
                .expectHeader()
                .value(HttpHeaders.CONTENT_DISPOSITION, disposition -> assertThat(disposition)
                        .as("a browser downloads it, and the file says which window it covers - "
                                + "one called export.csv is one nobody can identify a week later")
                        .contains("attachment")
                        .contains("sentinelflow-alerts-"))
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        List<String> lines = csv.lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("alertReference,status,priority,riskBand,finalScore");
        assertThat(csv).endsWith("\r\n");
    }

    @Test
    @DisplayName("a summary a spreadsheet would execute is exported as text instead")
    void neutralisesFormulasInTheExport() {
        // The summary is generated text, but it is generated from a transaction
        // reference that arrived through an ingestion endpoint which is open
        // until Phase 8 - so a cell in this file can contain characters somebody
        // outside the system chose.
        UUID alertId = alertIn(windowStart, "HIGH", "NEW");
        jdbc.update("UPDATE alerts SET summary = ? WHERE id = ?", "=HYPERLINK(\"https://example.invalid\")", alertId);

        String csv = export(analystToken, windowStart, windowEnd, 200);

        assertThat(csv).contains("\"'=HYPERLINK");
        assertThat(csv)
                .as("the cell must not begin with the character that makes it a formula")
                .doesNotContain(",=HYPERLINK");
    }

    @Test
    @DisplayName("an auditor can export, because reading is what an auditor does")
    void auditorsCanExport() {
        alertIn(windowStart, "HIGH", "NEW");

        assertThat(export(auditorToken, windowStart, windowEnd, 200)).contains("ALT-");
    }

    @Test
    @DisplayName("an empty window exports a header and nothing else")
    void exportsAnEmptyWindow() {
        // A header with no rows is a true answer. An empty body would be
        // indistinguishable from a failure that returned 200.
        String csv = export(analystToken, windowStart, windowEnd, 200);

        assertThat(csv.lines().toList()).hasSize(1);
    }

    // ----------------------------------------------------------------------- //
    // Fixtures and reads
    // ----------------------------------------------------------------------- //

    /** An alert created at a chosen instant, so a report over a window can count it exactly. */
    private UUID alertIn(Instant createdAt, String priority, String status) {
        UUID transactionId = fixtures.insertTransaction();
        UUID assessmentId = fixtures.insertAssessment(transactionId);
        UUID alertId = jdbc.queryForObject(
                """
                INSERT INTO alerts (
                    alert_reference, transaction_id, assessment_id, status, priority,
                    summary, risk_band, final_score)
                VALUES (?, ?, ?, ?, ?, 'Synthetic alert for a report test', 'HIGH', 71.50)
                RETURNING id
                """, UUID.class, "ALT-" + SchemaFixtures.next4(), transactionId, assessmentId, status, priority);
        // created_at is a database default, so it is set afterwards rather than
        // by the insert. The window is what this suite is about.
        jdbc.update("UPDATE alerts SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(createdAt), alertId);
        return alertId;
    }

    private void close(UUID alertId) {
        jdbc.update("UPDATE alerts SET status = 'CLOSED', closed_at = now() WHERE id = ?", alertId);
    }

    private String summary(String token, Instant from, Instant to, int expected) {
        return client.get()
                .uri(summaryUri(from, to))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private String export(String token, Instant from, Instant to, int expected) {
        return client.get()
                .uri(exportUri(from, to))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private static String summaryUri(Instant from, Instant to) {
        return "/api/v1/reports/alert-summary?from=" + from + "&to=" + to;
    }

    private static String exportUri(Instant from, Instant to) {
        return "/api/v1/reports/alerts.csv?from=" + from + "&to=" + to;
    }
}
