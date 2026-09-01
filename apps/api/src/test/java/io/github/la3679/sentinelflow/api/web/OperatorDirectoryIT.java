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

import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.security.TokenIssuer;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The operator directory, and the assignee an alert publishes, over HTTP against real PostgreSQL.
 *
 * <p>These are the two halves of "how an assignee's identifier resolves to a person" (ADR-0019), and
 * what is asserted here is mostly the difference between them: the directory answers <em>who may be
 * given an alert</em> and leaves people out, while the alert answers <em>who has this one</em> and
 * must still name somebody the directory would now exclude.
 *
 * <p><strong>The exclusions are the point.</strong> A picker that offered somebody the server would
 * refuse is a dead control, and a queue that blanked the assignee of every alert held by a disabled
 * operator would be losing information the audit trail depends on. Both are asserted rather than
 * assumed, against rows this test creates for exactly that purpose.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorDirectoryIT extends AbstractPostgresTest {

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

    private UUID analystId;
    private UUID administratorId;
    private UUID auditorId;
    private UUID disabledAnalystId;

    private String analystToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        analystId = operator("dir.analyst" + SUFFIX, "Dir Analyst " + SUFFIX, RoleCode.ANALYST, true);
        administratorId = operator("dir.admin" + SUFFIX, "Dir Administrator " + SUFFIX, RoleCode.ADMINISTRATOR, true);
        auditorId = operator("dir.auditor" + SUFFIX, "Dir Auditor " + SUFFIX, RoleCode.AUDITOR, true);
        disabledAnalystId = operator("dir.disabled" + SUFFIX, "Dir Disabled " + SUFFIX, RoleCode.ANALYST, false);

        analystToken = tokens.issue(analystId, List.of(RoleCode.ANALYST), Instant.now())
                .value();
        auditorToken = tokens.issue(auditorId, List.of(RoleCode.AUDITOR), Instant.now())
                .value();
    }

    // ----------------------------------------------------------------------- //
    // Who the directory offers
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the directory lists an analyst and an administrator, with the roles they hold")
    void listsOperatorsWhoCanWorkAnAlert() {
        List<JsonNode> operators = operators(analystToken);

        JsonNode analyst = byId(operators, analystId);
        assertThat(analyst.get("username").asString()).isEqualTo("dir.analyst" + SUFFIX);
        assertThat(analyst.get("displayName").asString()).isEqualTo("Dir Analyst " + SUFFIX);
        assertThat(roles(analyst)).containsExactly("ANALYST");

        assertThat(roles(byId(operators, administratorId))).containsExactly("ADMINISTRATOR");
    }

    @Test
    @DisplayName("an auditor is not offered, because assigning one would create work they may not do")
    void excludesAuditors() {
        assertThat(ids(operators(analystToken)))
                .as("ADR-0012 §4 makes the role read-only, and the assignment endpoint refuses it. "
                        + "Offering one here would draw a control the server would reject.")
                .doesNotContain(auditorId);
    }

    @Test
    @DisplayName("a disabled operator is not offered, because work given to them is never cleared")
    void excludesDisabledOperators() {
        assertThat(ids(operators(analystToken))).doesNotContain(disabledAnalystId);
    }

    @Test
    @DisplayName("the system principal is not offered: it raises alerts and never works them")
    void excludesTheSystemPrincipal() {
        UUID systemId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'system'", UUID.class);

        assertThat(ids(operators(analystToken)))
                .as("it holds SYSTEM rather than a working role, and it cannot hold a token at all")
                .doesNotContain(systemId);
    }

    @Test
    @DisplayName("an auditor may read the directory, because they already see every assignee")
    void auditorsMayReadTheDirectory() {
        assertThat(ids(operators(auditorToken)))
                .as("withholding a list of names from a role that can read every alert's assignee "
                        + "would protect nothing")
                .contains(analystId);
    }

    @Test
    @DisplayName("the directory needs a token")
    void refusesAnUnauthenticatedReader() {
        client.get()
                .uri("/api/v1/operators")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("the page size is bounded, and a request for more is refused rather than clamped")
    void boundsThePageSize() {
        client.get()
                .uri("/api/v1/operators?size=201")
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()
                .expectStatus()
                .isEqualTo(422);
    }

    @Test
    @DisplayName("it is ordered by display name, so a picker reads the way a person expects")
    void ordersByDisplayName() {
        List<String> names = operators(analystToken).stream()
                .map(operator -> operator.get("displayName").asString())
                .toList();

        assertThat(names).isSorted();
    }

    // ----------------------------------------------------------------------- //
    // Who the alert says holds it
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("an assigned alert names the person, not only the identifier")
    void resolvesTheAssigneeOnAnAlert() {
        UUID alertId = newAlert();
        assign(alertId, analystId, 0);

        JsonNode alert = readAlert(alertId);

        assertThat(alert.get("assigneeId").asString()).isEqualTo(analystId.toString());
        assertThat(alert.get("assignee").get("operatorId").asString()).isEqualTo(analystId.toString());
        assertThat(alert.get("assignee").get("displayName").asString()).isEqualTo("Dir Analyst " + SUFFIX);
        assertThat(alert.get("assignee").get("username").asString()).isEqualTo("dir.analyst" + SUFFIX);
    }

    @Test
    @DisplayName("an unassigned alert has a null assignee beside its null identifier")
    void publishesANullAssigneeWhenUnassigned() {
        JsonNode alert = readAlert(newAlert());

        assertThat(alert.get("assigneeId").isNull()).isTrue();
        assertThat(alert.get("assignee").isNull())
                .as("both are null together; a placeholder object would be a person who does not exist")
                .isTrue();
    }

    @Test
    @DisplayName("the queue resolves the assignee on every row, not only on the single read")
    void resolvesTheAssigneeOnTheQueue() {
        UUID alertId = newAlert();
        assign(alertId, analystId, 0);

        JsonNode page = MAPPER.readTree(client.get()
                .uri("/api/v1/alerts?assigneeId=" + analystId)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());

        JsonNode row = page.get("content").get(0);
        assertThat(row.get("assignee").get("displayName").asString()).isEqualTo("Dir Analyst " + SUFFIX);
    }

    @Test
    @DisplayName("a queue page holding an unassigned alert is served, not a 500")
    void servesAQueuePageWithUnassignedAlerts() {
        newAlert();

        // The regression this exists for: resolve() answers a page with nothing
        // assigned using Map.of(), and Map.of().get(null) throws rather than
        // returning null. Every unfiltered queue read answered 500 until the
        // lookup was guarded, and the first version of this suite missed it
        // because every query here filtered by assignee.
        JsonNode page = MAPPER.readTree(client.get()
                .uri("/api/v1/alerts?size=200")
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());

        List<JsonNode> rows = new ArrayList<>();
        page.get("content").forEach(rows::add);
        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().anyMatch(row -> row.get("assignee").isNull()))
                .as("at least one row is unassigned, which is the case that used to throw")
                .isTrue();
    }

    @Test
    @DisplayName("an alert held by an operator who has since been disabled still names them")
    void keepsNamingADisabledAssignee() {
        UUID alertId = newAlert();
        assign(alertId, analystId, 0);

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", analystId);
        try {
            assertThat(ids(operators(auditorToken)))
                    .as("the directory stops offering them, which is the whole point of the filter")
                    .doesNotContain(analystId);

            assertThat(readAlert(alertId).get("assignee").get("displayName").asString())
                    .as("and the alert still says who has it. An alert assigned last week to somebody "
                            + "who has left is not unassigned, and forgetting them would lose what the "
                            + "audit trail is for.")
                    .isEqualTo("Dir Analyst " + SUFFIX);
        } finally {
            jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", analystId);
        }
    }

    // ----------------------------------------------------------------------- //
    // The server still decides
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the server refuses an assignment to an auditor even though nothing offered them")
    void theServerStillRefusesWhatTheDirectoryNeverOffered() {
        UUID alertId = newAlert();

        String body = client.put()
                .uri("/api/v1/alerts/" + alertId + "/assignment")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .body("{\"assigneeId\":\"" + auditorId + "\",\"expectedVersion\":0}")
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                .as("the directory is an affordance; this refusal is the authorization, and a client "
                        + "is free to send an identifier the directory never gave it")
                .doesNotContain(auditorId.toString().substring(0, 8) + "\":\"exists");
    }

    // ----------------------------------------------------------------------- //
    // Helpers
    // ----------------------------------------------------------------------- //

    private List<JsonNode> operators(String token) {
        JsonNode page = MAPPER.readTree(client.get()
                .uri("/api/v1/operators?size=200")
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());

        List<JsonNode> content = new ArrayList<>();
        page.get("content").forEach(content::add);
        return content;
    }

    private JsonNode readAlert(UUID alertId) {
        return MAPPER.readTree(client.get()
                .uri("/api/v1/alerts/" + alertId)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());
    }

    private void assign(UUID alertId, UUID assigneeId, long expectedVersion) {
        client.put()
                .uri("/api/v1/alerts/" + alertId + "/assignment")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .body("{\"assigneeId\":\"" + assigneeId + "\",\"expectedVersion\":" + expectedVersion + "}")
                .exchange()
                .expectStatus()
                .isOk();
    }

    private static List<UUID> ids(List<JsonNode> operators) {
        return operators.stream()
                .map(operator -> UUID.fromString(operator.get("operatorId").asString()))
                .toList();
    }

    private static List<String> roles(JsonNode operator) {
        List<String> held = new ArrayList<>();
        operator.get("roles").forEach(role -> held.add(role.asString()));
        return held;
    }

    private static JsonNode byId(List<JsonNode> operators, UUID operatorId) {
        return operators.stream()
                .filter(operator -> operator.get("operatorId").asString().equals(operatorId.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The directory did not list " + operatorId));
    }

    private UUID operator(String username, String displayName, RoleCode role, boolean active) {
        Integer existing =
                jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username);
        if (existing == null || existing == 0) {
            jdbc.update(
                    "INSERT INTO users (username, display_name, status) VALUES (?, ?, ?)",
                    username,
                    displayName,
                    active ? "ACTIVE" : "DISABLED");
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
}
