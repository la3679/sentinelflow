/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.github.la3679.sentinelflow.api.domain.FeedbackLabel;
import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.security.TokenIssuer;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading the queue, and recording a verdict on what is in it.
 *
 * <p>The ordering assertions are the point of the first half. A queue is a list of work rather than
 * a list of rows, and every property of that ordering — open before closed, urgent before high,
 * oldest before newest — is a decision somebody could reasonably have made differently, so each is
 * stated here rather than left to whatever the plan produces.
 *
 * <p><strong>Every alert this suite reads is one it created.</strong> One container serves the whole
 * fork and other suites leave alerts behind, so nothing here asserts a total or a first element: the
 * assertions are about the relative order of this suite's own rows and about counts filtered to
 * them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertQueueIT extends AbstractPostgresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String SUFFIX = Long.toString(System.nanoTime() % 100_000L);

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
    private UUID analystId;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        analystId = operator("queue.analyst" + SUFFIX, RoleCode.ANALYST);
        analystToken = tokens.issue(analystId, List.of(RoleCode.ANALYST), Instant.now())
                .value();
        auditorToken = tokens.issue(
                        operator("queue.auditor" + SUFFIX, RoleCode.AUDITOR), List.of(RoleCode.AUDITOR), Instant.now())
                .value();
    }

    // ----------------------------------------------------------------------- //
    // The queue
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the queue puts urgent work above high, and older work above newer")
    void ordersTheQueue() {
        UUID high = newAlert("HIGH");
        UUID urgent = newAlert("URGENT");
        UUID laterHigh = newAlert("HIGH");

        List<String> mine = idsOf(get("?size=200", analystToken, 200), List.of(high, urgent, laterHigh));

        // Priority first because that is what a priority is for; then oldest
        // first, because work that has waited longest should be picked up next.
        assertThat(mine).containsExactly(urgent.toString(), high.toString(), laterHigh.toString());
    }

    @Test
    @DisplayName("closed alerts sink below open ones, whatever their priority")
    void openWorkComesFirst() {
        UUID urgentClosed = newAlert("URGENT");
        UUID lowOpen = newAlert("LOW");
        close(urgentClosed);

        List<String> mine = idsOf(get("?size=200", analystToken, 200), List.of(urgentClosed, lowOpen));

        // A queue is a list of work. A closed URGENT alert outranking an open
        // LOW one would put finished work at the top of somebody's day.
        assertThat(mine).containsExactly(lowOpen.toString(), urgentClosed.toString());
    }

    @Test
    @DisplayName("the filters are optional and combine")
    void filtersTheQueue() {
        UUID mine = newAlert("HIGH");
        UUID somebodyElses = newAlert("HIGH");
        assign(mine, analystId);

        JsonNode page = get("?assigneeId=" + analystId + "&status=NEW&size=200", analystToken, 200);

        List<String> ids = idsOf(page, List.of(mine, somebodyElses));
        assertThat(ids).containsExactly(mine.toString());
    }

    @Test
    @DisplayName("an auditor can read the queue")
    void auditorsCanRead() {
        newAlert("HIGH");

        // Read-only is a statement about mutations. An auditor who could not see
        // the queue could not audit anything in it.
        get("?size=5", auditorToken, 200);
    }

    @Test
    @DisplayName("an oversize page is refused rather than clamped, here as everywhere")
    void refusesAnOversizePage() {
        get("?size=500", analystToken, 422);
    }

    @Test
    @DisplayName("one alert is readable by its identifier, and an unknown one is a not-found")
    void readsOneAlert() {
        UUID alertId = newAlert("HIGH");

        JsonNode body = MAPPER.readTree(client.get()
                .uri("/api/v1/alerts/" + alertId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());

        assertThat(body.get("alertId").asString()).isEqualTo(alertId.toString());
        assertThat(body.get("version").asLong()).isEqualTo(0L);

        client.get()
                .uri("/api/v1/alerts/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    // ----------------------------------------------------------------------- //
    // Feedback
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a verdict is recorded against the assessment, citing the alert")
    void recordsAVerdict() {
        UUID alertId = newAlert("HIGH");

        JsonNode body =
                MAPPER.readTree(feedback(alertId, analystToken, FeedbackLabel.TRUE_POSITIVE, "Card testing", 200));

        assertThat(body.get("label").asString()).isEqualTo("TRUE_POSITIVE");
        assertThat(body.get("alertId").asString()).isEqualTo(alertId.toString());
        assertThat(body.get("assessmentId").asString())
                .as("the label is about the decision: rescoring writes a new assessment, and a "
                        + "label on the alert would follow one it was never given about")
                .isEqualTo(assessmentOf(alertId).toString());
        assertThat(feedbackCount(alertId)).isEqualTo(1);
    }

    @Test
    @DisplayName("changing your mind replaces the label rather than adding a second one")
    void revisesAVerdict() {
        UUID alertId = newAlert("HIGH");
        feedback(alertId, analystToken, FeedbackLabel.TRUE_POSITIVE, null, 200);

        JsonNode body = MAPPER.readTree(
                feedback(alertId, analystToken, FeedbackLabel.FALSE_POSITIVE, "Customer confirmed", 200));

        assertThat(body.get("label").asString()).isEqualTo("FALSE_POSITIVE");
        assertThat(feedbackCount(alertId))
                .as("two opposite labels from one person about one decision would poison a "
                        + "training set quietly, and nothing could choose between them afterwards")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an auditor cannot record a verdict")
    void auditorsCannotLabel() {
        UUID alertId = newAlert("HIGH");

        feedback(alertId, auditorToken, FeedbackLabel.TRUE_POSITIVE, null, 403);

        assertThat(feedbackCount(alertId)).isZero();
    }

    @Test
    @DisplayName("a verdict cannot be recorded on a closed alert")
    void closedAlertsTakeNoVerdict() {
        UUID alertId = newAlert("HIGH");
        close(alertId);

        feedback(alertId, analystToken, FeedbackLabel.INCONCLUSIVE, null, 409);

        assertThat(feedbackCount(alertId)).isZero();
    }

    @Test
    @DisplayName("a label the enum does not name is refused")
    void refusesAnUnknownLabel() {
        UUID alertId = newAlert("HIGH");

        client.put()
                .uri("/api/v1/alerts/" + alertId + "/feedback")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"label\":\"PROBABLY\"}")
                .exchange()
                .expectStatus()
                .isBadRequest();

        assertThat(feedbackCount(alertId)).isZero();
    }

    // ----------------------------------------------------------------------- //
    // Fixtures and reads
    // ----------------------------------------------------------------------- //

    private UUID operator(String username, RoleCode role) {
        Integer existing =
                jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username);
        if (existing == null || existing == 0) {
            jdbc.update(
                    "INSERT INTO users (username, display_name, status) VALUES (?, ?, 'ACTIVE')",
                    username,
                    "Integration test " + role);
            jdbc.update("""
                    INSERT INTO user_roles (user_id, role_id)
                    SELECT u.id, r.id FROM users u, roles r WHERE u.username = ? AND r.code = ?
                    """, username, role.name());
        }
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, username);
    }

    /** A NEW alert at a chosen priority, inserted directly so this suite owns its own rows. */
    private UUID newAlert(String priority) {
        UUID transactionId = fixtures.insertTransaction();
        UUID assessmentId = fixtures.insertAssessment(transactionId);
        UUID alertId = jdbc.queryForObject(
                """
                INSERT INTO alerts (
                    alert_reference, transaction_id, assessment_id, status, priority,
                    summary, risk_band, final_score)
                VALUES (?, ?, ?, 'NEW', ?, 'Synthetic alert for a queue test', 'HIGH', 71.50)
                RETURNING id
                """, UUID.class, "ALT-" + SchemaFixtures.next4(), transactionId, assessmentId, priority);
        // Distinct creation instants, because the queue's third ordering term is
        // oldest first and rows written in one millisecond cannot demonstrate it.
        jdbc.update(
                "UPDATE alerts SET created_at = now() + (? * interval '1 second') WHERE id = ?", created++, alertId);
        return alertId;
    }

    private static int created = 0;

    private void close(UUID alertId) {
        jdbc.update("UPDATE alerts SET status = 'CLOSED', closed_at = now() WHERE id = ?", alertId);
    }

    private void assign(UUID alertId, UUID assigneeId) {
        jdbc.update("UPDATE alerts SET assignee_id = ? WHERE id = ?", assigneeId, alertId);
    }

    private JsonNode get(String query, String token, int expected) {
        return MAPPER.readTree(client.get()
                .uri("/api/v1/alerts" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());
    }

    private String feedback(UUID alertId, String token, FeedbackLabel label, String reason, int expected) {
        return client.put()
                .uri("/api/v1/alerts/" + alertId + "/feedback")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(new FeedbackBody(label.name(), reason)))
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    /** This suite's own alerts, in the order the queue returned them. */
    private static List<String> idsOf(JsonNode page, List<UUID> mine) {
        List<String> wanted = mine.stream().map(UUID::toString).toList();
        List<String> found = new ArrayList<>();
        page.get("content").forEach(node -> {
            String id = node.get("alertId").asString();
            if (wanted.contains(id)) {
                found.add(id);
            }
        });
        return found;
    }

    private UUID assessmentOf(UUID alertId) {
        return jdbc.queryForObject("SELECT assessment_id FROM alerts WHERE id = ?", UUID.class, alertId);
    }

    private int feedbackCount(UUID alertId) {
        return jdbc.queryForObject("SELECT count(*) FROM analyst_feedback WHERE alert_id = ?", Integer.class, alertId);
    }

    private record FeedbackBody(String label, String reason) {}
}
