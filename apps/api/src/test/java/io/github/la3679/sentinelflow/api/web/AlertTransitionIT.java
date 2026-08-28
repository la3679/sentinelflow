/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import static org.assertj.core.api.Assertions.assertThat;

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

import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.security.TokenIssuer;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transition endpoint as a client meets it: over HTTP, through the filter chain, with a real
 * token.
 *
 * <p>{@code AlertServiceIT} covers what the workflow writes. This covers what a caller sees, which
 * is a different set of questions and mostly about refusals — the status code an illegal move
 * produces, what a stale version answers, and whether an auditor's token is stopped by the server
 * rather than by a disabled button somewhere.
 *
 * <p><strong>The tokens are issued directly rather than by logging in.</strong> Logging in is
 * {@code OperatorAuthenticationIT}'s subject; here it would be three extra requests per test to
 * arrive at the same bearer string. What matters is that these tokens go through the same filter
 * chain a client's would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertTransitionIT extends AbstractPostgresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private TokenIssuer tokens;

    @Autowired
    private JdbcTemplate jdbc;

    private SchemaFixtures fixtures;
    private RestTestClient client;
    private String analystToken;
    private String administratorToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // The system principal's identifier, because the token's subject has to
        // be a real users row: every audit row this endpoint writes stores it as
        // a foreign key, and a random UUID would fail at the constraint rather
        // than at the assertion. What the token proves here is the role.
        UUID operator = fixtures.systemUserId();
        analystToken = tokenFor(operator, RoleCode.ANALYST);
        administratorToken = tokenFor(operator, RoleCode.ADMINISTRATOR);
        auditorToken = tokenFor(operator, RoleCode.AUDITOR);
    }

    // ----------------------------------------------------------------------- //
    // The move
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("an analyst picks up an alert and gets it back at its new version")
    void transitionsAnAlert() {
        UUID alertId = newAlert();

        JsonNode body = MAPPER.readTree(transition(alertId, analystToken, AlertStatus.IN_REVIEW, 0, "Picked up", 200));

        assertThat(body.get("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(body.get("version").asLong())
                .as("returned rather than left to be re-read: a client that wants to act again "
                        + "needs the new token, and this request already knows it")
                .isEqualTo(1L);
        assertThat(body.get("closedAt").isNull()).isTrue();
        assertThat(body.get("alertReference").asString()).matches("ALT-[0-9]{4}");
    }

    @Test
    @DisplayName("an administrator closes an alert without a disposition")
    void administratorClosesAnAlert() {
        UUID alertId = newAlert();

        JsonNode body =
                MAPPER.readTree(transition(alertId, administratorToken, AlertStatus.CLOSED, 0, "Duplicate", 200));

        assertThat(body.get("status").asString()).isEqualTo("CLOSED");
        assertThat(body.get("closedAt").isNull())
                .as("a terminal alert carries the time the investigation ended")
                .isFalse();
    }

    // ----------------------------------------------------------------------- //
    // The refusals
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("an auditor is refused by the server, not by a disabled button")
    void auditorsCannotMutate() {
        UUID alertId = newAlert();

        String body = transition(alertId, auditorToken, AlertStatus.IN_REVIEW, 0, null, 403);

        assertThat(MAPPER.readTree(body).get("title").asString()).isEqualTo("Insufficient role");
        assertThat(statusOf(alertId)).as("and nothing moved").isEqualTo("NEW");
    }

    @Test
    @DisplayName("an analyst cannot close an alert without a disposition")
    void analystsCannotAdministrativelyClose() {
        UUID alertId = newAlert();

        // The one move reserved to an administrator. It is the only transition
        // that takes work off a queue while recording nothing about the
        // transaction, which is a supervisory decision rather than a disposition.
        String body = transition(alertId, analystToken, AlertStatus.CLOSED, 0, null, 403);

        assertThat(MAPPER.readTree(body).get("detail").asString()).contains("ADMINISTRATOR");
        assertThat(statusOf(alertId)).isEqualTo("NEW");
    }

    @Test
    @DisplayName("an anonymous request never reaches the workflow")
    void anonymousRequestsAreRefused() {
        UUID alertId = newAlert();

        client.post()
                .uri("/api/v1/alerts/" + alertId + "/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(AlertStatus.IN_REVIEW, 0, null))
                .exchange()
                .expectStatus()
                .isUnauthorized();

        assertThat(statusOf(alertId)).isEqualTo("NEW");
    }

    @Test
    @DisplayName("an illegal move is a conflict, and the response says what is legal instead")
    void illegalMovesAreAConflict() {
        UUID alertId = newAlert();

        // 409 rather than 400: the request is well formed and the target is a
        // real status. What refuses it is the state the alert is in, and that
        // can change between one request and the next.
        JsonNode problem =
                MAPPER.readTree(transition(alertId, analystToken, AlertStatus.CONFIRMED_SUSPICIOUS, 0, null, 409));

        assertThat(problem.get("title").asString()).isEqualTo("Illegal transition");
        assertThat(problem.get("currentStatus").asString()).isEqualTo("NEW");

        List<String> legal = new java.util.ArrayList<>();
        problem.get("legalTargets").forEach(node -> legal.add(node.asString()));
        assertThat(legal)
                .as("a property of the state machine rather than of this alert, so naming it " + "discloses nothing")
                .containsExactly("CLOSED", "IN_REVIEW");
    }

    @Test
    @DisplayName("a stale version is a conflict that names the version the alert is actually at")
    void staleVersionsAreAConflict() {
        UUID alertId = newAlert();
        transition(alertId, analystToken, AlertStatus.IN_REVIEW, 0, null, 200);

        JsonNode problem = MAPPER.readTree(transition(alertId, analystToken, AlertStatus.ESCALATED, 0, null, 409));

        assertThat(problem.get("title").asString()).isEqualTo("The alert has changed");
        assertThat(problem.get("expectedVersion").asLong()).isEqualTo(0L);
        assertThat(problem.get("currentVersion").asLong())
                .as("so a client can re-read and decide again rather than guess")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("an unknown alert is a not-found")
    void unknownAlertsAreNotFound() {
        transition(UUID.randomUUID(), analystToken, AlertStatus.IN_REVIEW, 0, null, 404);
    }

    @Test
    @DisplayName("a request without a version is refused before anything is read")
    void theVersionIsRequired() {
        UUID alertId = newAlert();

        // Optional would make the safe call the longer one to write and the
        // unsafe call the default.
        client.post()
                .uri("/api/v1/alerts/" + alertId + "/transition")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"targetStatus\":\"IN_REVIEW\"}")
                .exchange()
                .expectStatus()
                // 422, which is what this API answers for a well-formed request
                // whose fields do not satisfy the contract. The transactions
                // endpoint has answered the same way since Phase 3.
                .isEqualTo(422);

        assertThat(statusOf(alertId)).isEqualTo("NEW");
    }

    // ----------------------------------------------------------------------- //
    // Fixtures and reads
    // ----------------------------------------------------------------------- //

    private String tokenFor(UUID userId, RoleCode role) {
        return tokens.issue(userId, List.of(role), java.time.Instant.now()).value();
    }

    private UUID newAlert() {
        UUID transactionId = fixtures.insertTransaction();
        return fixtures.insertAlert(transactionId, fixtures.insertAssessment(transactionId));
    }

    private String transition(UUID alertId, String token, AlertStatus target, long version, String note, int expected) {
        return client.post()
                .uri("/api/v1/alerts/" + alertId + "/transition")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(target, version, note))
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private static String requestBody(AlertStatus target, long version, String note) {
        return MAPPER.writeValueAsString(new TransitionBody(target.name(), version, note));
    }

    private String statusOf(UUID alertId) {
        return jdbc.queryForObject("SELECT status FROM alerts WHERE id = ?", String.class, alertId);
    }

    /** The wire shape, written out rather than reusing the DTO, so what is asserted is what a client sends. */
    private record TransitionBody(String targetStatus, long expectedVersion, String note) {}
}
