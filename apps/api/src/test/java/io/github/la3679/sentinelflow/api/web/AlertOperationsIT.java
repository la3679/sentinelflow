/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.security.TokenIssuer;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assignment, notes, and reading an alert's history, over HTTP.
 *
 * <p>What is asserted here is mostly what the endpoints refuse and what they write, because those
 * are the parts a schema and a signature cannot state: that an assignment to an auditor is rejected
 * for a reason that does not name the directory, that a repeated assignment writes nothing at all,
 * that a note needs no version and cannot conflict, and that an auditor can read the history they
 * are not allowed to add to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertOperationsIT extends AbstractPostgresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Unique per run: one container serves the whole fork and users outlive this suite. */
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
    private UUID auditorId;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        analystId = operator("ops.analyst" + SUFFIX, RoleCode.ANALYST);
        auditorId = operator("ops.auditor" + SUFFIX, RoleCode.AUDITOR);
        analystToken = tokens.issue(analystId, List.of(RoleCode.ANALYST), Instant.now())
                .value();
        auditorToken = tokens.issue(auditorId, List.of(RoleCode.AUDITOR), Instant.now())
                .value();
    }

    // ----------------------------------------------------------------------- //
    // Assignment
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("an alert is given to an analyst, and the history says who did it")
    void assignsAnAlert() {
        UUID alertId = newAlert();

        JsonNode body = MAPPER.readTree(assign(alertId, analystToken, analystId, 0, "Mine", 200));

        assertThat(body.get("assigneeId").asString()).isEqualTo(analystId.toString());
        assertThat(body.get("version").asLong()).isEqualTo(1L);
        assertThat(body.get("status").asString())
                .as("assignment does not move the alert; picking work up and starting it are two "
                        + "decisions and the queue must not lie about either")
                .isEqualTo("NEW");

        Map<String, Object> action = jdbc.queryForMap(
                "SELECT * FROM alert_actions WHERE alert_id = ? AND action_type = 'ASSIGNED'", alertId);
        assertThat(action.get("actor_id")).isEqualTo(analystId);
        assertThat(action.get("note")).isEqualTo("Mine");
    }

    @Test
    @DisplayName("releasing an alert is an unassignment, not an assignment to nobody")
    void releasesAnAlert() {
        UUID alertId = newAlert();
        assign(alertId, analystToken, analystId, 0, null, 200);

        JsonNode body = MAPPER.readTree(assign(alertId, analystToken, null, 1, "Not mine after all", 200));

        assertThat(body.get("assigneeId").isNull()).isTrue();
        assertThat(actionCount(alertId, "UNASSIGNED"))
                .as("an audit reader asks which happened, so the two are distinguished on the row "
                        + "even though the event calls both an assignment")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("assigning the same person twice writes nothing the second time")
    void repeatedAssignmentIsANoOp() {
        UUID alertId = newAlert();
        assign(alertId, analystToken, analystId, 0, null, 200);

        // Same assignee, and the version has moved on - so this is a retry
        // rather than a stale request. A history row saying an alert was
        // assigned to whoever already held it is noise in the one place noise
        // is most expensive.
        JsonNode body = MAPPER.readTree(assign(alertId, analystToken, analystId, 1, null, 200));

        assertThat(body.get("version").asLong())
                .as("nothing was written, so the version did not move either")
                .isEqualTo(1L);
        assertThat(actionCount(alertId, "ASSIGNED")).isEqualTo(1);
        assertThat(updatedEventCount(alertId))
                .as("and no second event announcing a change that did not happen")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an auditor cannot be given an alert to work")
    void auditorsCannotBeAssigned() {
        UUID alertId = newAlert();

        // A real user, an active account, and a role that is read-only. The
        // foreign key cannot see any of that.
        JsonNode problem = MAPPER.readTree(assign(alertId, analystToken, auditorId, 0, null, 422));

        assertThat(problem.get("title").asString()).isEqualTo("Cannot be assigned");
        assertThat(problem.get("detail").asString())
                .as("says a user cannot be given work, never which users exist")
                .doesNotContain(auditorId.toString());
        assertThat(assigneeOf(alertId)).isNull();
    }

    @Test
    @DisplayName("an assignee who does not exist is refused before the foreign key sees it")
    void unknownAssigneesAreRefused() {
        UUID alertId = newAlert();

        assign(alertId, analystToken, UUID.randomUUID(), 0, null, 422);

        assertThat(assigneeOf(alertId)).isNull();
    }

    @Test
    @DisplayName("a closed alert cannot be assigned")
    void closedAlertsCannotBeAssigned() {
        UUID alertId = newAlert();
        transitionTo(alertId, AlertStatus.IN_REVIEW, 0);
        transitionTo(alertId, AlertStatus.CONFIRMED_SUSPICIOUS, 1);

        JsonNode problem = MAPPER.readTree(assign(alertId, analystToken, analystId, 2, null, 409));

        assertThat(problem.get("currentStatus").asString()).isEqualTo("CONFIRMED_SUSPICIOUS");
        assertThat(assigneeOf(alertId))
                .as("an assignee on a closed alert would sit in what is on this analyst's desk for ever")
                .isNull();
    }

    @Test
    @DisplayName("an auditor cannot assign an alert either")
    void auditorsCannotAssign() {
        UUID alertId = newAlert();

        assign(alertId, auditorToken, analystId, 0, null, 403);

        assertThat(assigneeOf(alertId)).isNull();
    }

    // ----------------------------------------------------------------------- //
    // Notes
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a note is written to the history and does not touch the alert")
    void addsANote() {
        UUID alertId = newAlert();

        JsonNode body =
                MAPPER.readTree(note(alertId, analystToken, "Called the customer; card was in their pocket", 201));

        assertThat(body.get("actionType").asString()).isEqualTo("NOTE_ADDED");
        assertThat(body.get("note").asString()).contains("Called the customer");
        assertThat(body.get("actorRole").asString()).isEqualTo("ANALYST");
        assertThat(versionOf(alertId))
                .as("a note is appended rather than replacing anything, so the alert did not change")
                .isEqualTo(0L);
        assertThat(updatedEventCount(alertId))
                .as("and nothing was published: an analyst's own words belong on a detail page "
                        + "somebody opened, not on a topic that leaves this service")
                .isZero();
    }

    @Test
    @DisplayName("two notes at the same version both succeed")
    void notesDoNotConflict() {
        UUID alertId = newAlert();

        note(alertId, analystToken, "First", 201);
        note(alertId, analystToken, "Second", 201);

        // Neither carried a version and neither needed one. Refusing the second
        // would be refusing it for a reason no user could act on.
        assertThat(actionCount(alertId, "NOTE_ADDED")).isEqualTo(2);
    }

    @Test
    @DisplayName("an empty note is refused at the boundary rather than by the constraint")
    void blankNotesAreRefused() {
        UUID alertId = newAlert();

        note(alertId, analystToken, "   ", 422);

        assertThat(actionCount(alertId, "NOTE_ADDED")).isZero();
    }

    @Test
    @DisplayName("a closed alert cannot be annotated")
    void closedAlertsCannotBeAnnotated() {
        UUID alertId = newAlert();
        transitionTo(alertId, AlertStatus.IN_REVIEW, 0);
        transitionTo(alertId, AlertStatus.DISMISSED_FALSE_POSITIVE, 1);

        // A note added after a disposition reads as though it informed one.
        note(alertId, analystToken, "Actually, one more thing", 409);

        assertThat(actionCount(alertId, "NOTE_ADDED")).isZero();
    }

    // ----------------------------------------------------------------------- //
    // History
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the history reads newest first, and an auditor can read it")
    void readsTheHistory() {
        UUID alertId = newAlert();
        transitionTo(alertId, AlertStatus.IN_REVIEW, 0);
        note(alertId, analystToken, "Looking now", 201);

        // Read-only describes what somebody may do, not what they may see. An
        // auditor who could not read the audit trail would be a contradiction.
        JsonNode page = MAPPER.readTree(history(alertId, auditorToken, "?page=0&size=10", 200));

        List<String> types = new ArrayList<>();
        page.get("content").forEach(node -> types.add(node.get("actionType").asString()));
        assertThat(types).containsExactly("NOTE_ADDED", "TRANSITIONED");
        assertThat(page.get("page").get("totalElements").asInt())
                .as("a real count rather than an estimate, and this alert has no CREATED row "
                        + "because the fixture inserted it rather than the raiser")
                .isEqualTo(2);
        assertThat(page.get("page").get("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the history is paged, and the pages do not overlap")
    void pagesTheHistory() {
        UUID alertId = newAlert();
        note(alertId, analystToken, "One", 201);
        note(alertId, analystToken, "Two", 201);
        note(alertId, analystToken, "Three", 201);

        String first = MAPPER.readTree(history(alertId, analystToken, "?page=0&size=2", 200))
                .get("content")
                .get(0)
                .get("actionId")
                .asString();
        JsonNode second = MAPPER.readTree(history(alertId, analystToken, "?page=1&size=2", 200));

        assertThat(second.get("content").size()).isEqualTo(1);
        assertThat(second.get("content").get(0).get("actionId").asString())
                .as("the identifier breaks ties on occurred_at, so which rows land on which page "
                        + "does not vary between two identical requests")
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("a page size above the cap is refused rather than clamped")
    void refusesAnOversizePage() {
        UUID alertId = newAlert();

        // Silently returning less than was asked for is how a client ends up
        // with a quiet data-loss bug: it pages until it sees fewer rows than it
        // requested and concludes it has reached the end.
        JsonNode problem = MAPPER.readTree(history(alertId, analystToken, "?size=500", 422));

        assertThat(problem.get("title").asString()).isEqualTo("Validation failed");
    }

    @Test
    @DisplayName("the history of an alert that does not exist is a not-found, never an empty page")
    void unknownAlertsHaveNoHistory() {
        // An alert with no history does not exist - the raiser writes its first
        // row - so an empty page would be an answer that cannot be true.
        history(UUID.randomUUID(), analystToken, "", 404);
    }

    @Test
    @DisplayName("reading the history still requires a token")
    void anonymousReadsAreRefused() {
        UUID alertId = newAlert();

        client.get()
                .uri("/api/v1/alerts/" + alertId + "/history")
                .exchange()
                .expectStatus()
                .isUnauthorized();
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

    private UUID newAlert() {
        UUID transactionId = fixtures.insertTransaction();
        return fixtures.insertAlert(transactionId, fixtures.insertAssessment(transactionId));
    }

    private String assign(UUID alertId, String token, UUID assigneeId, long version, String note, int expected) {
        return client.put()
                .uri("/api/v1/alerts/" + alertId + "/assignment")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(
                        new AssignmentBody(assigneeId == null ? null : assigneeId.toString(), version, note)))
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private String note(UUID alertId, String token, String text, int expected) {
        return client.post()
                .uri("/api/v1/alerts/" + alertId + "/notes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(new NoteBody(text)))
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private String history(UUID alertId, String token, String query, int expected) {
        return client.get()
                .uri("/api/v1/alerts/" + alertId + "/history" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private void transitionTo(UUID alertId, AlertStatus target, long version) {
        client.post()
                .uri("/api/v1/alerts/" + alertId + "/transition")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(new TransitionBody(target.name(), version)))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private int actionCount(UUID alertId, String actionType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM alert_actions WHERE alert_id = ? AND action_type = ?",
                Integer.class,
                alertId,
                actionType);
    }

    private int updatedEventCount(UUID alertId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'alert.updated' AND aggregate_id = ?",
                Integer.class,
                alertId);
    }

    private UUID assigneeOf(UUID alertId) {
        return jdbc.queryForObject("SELECT assignee_id FROM alerts WHERE id = ?", UUID.class, alertId);
    }

    private long versionOf(UUID alertId) {
        return jdbc.queryForObject("SELECT version FROM alerts WHERE id = ?", Long.class, alertId);
    }

    private record AssignmentBody(String assigneeId, long expectedVersion, String note) {}

    private record NoteBody(String note) {}

    private record TransitionBody(String targetStatus, long expectedVersion) {}
}
