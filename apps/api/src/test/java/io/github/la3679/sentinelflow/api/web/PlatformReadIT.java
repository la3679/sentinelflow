/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two screens that describe the platform rather than the work in it.
 *
 * <p><strong>No scoring service is running in this suite, and that is the interesting case.</strong>
 * Both endpoints are compositions with one half that can be unreachable, and both were built so that
 * half being missing produces an answer rather than a failure — so the run where it is missing is
 * the one worth having. The model half's happy path is covered by {@code ScoringClientTests} against
 * a stub that does answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformReadIT extends AbstractPostgresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String SUFFIX = Long.toString(System.nanoTime() % 100_000L);

    @LocalServerPort
    private int port;

    @Autowired
    private TokenIssuer tokens;

    @Autowired
    private JdbcTemplate jdbc;

    private RestTestClient client;
    private String analystToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        analystToken = tokens.issue(
                        operator("platform.analyst" + SUFFIX, RoleCode.ANALYST),
                        List.of(RoleCode.ANALYST),
                        Instant.now())
                .value();
        auditorToken = tokens.issue(
                        operator("platform.auditor" + SUFFIX, RoleCode.AUDITOR),
                        List.of(RoleCode.AUDITOR),
                        Instant.now())
                .value();
    }

    // ----------------------------------------------------------------------- //
    // The model and the policy
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the policy answers even when the scoring service does not")
    void answersWithThePolicyAlone() {
        JsonNode body = get("/api/v1/models/active", analystToken, 200);

        // 200 rather than 503. The policy half is this service's own and is half
        // of what the screen is for; blanking it during a scoring outage would
        // hide exactly what somebody would be looking for.
        assertThat(body.get("modelAvailable").asBoolean()).isFalse();
        assertThat(body.get("modelVersion").isNull()).isTrue();
        assertThat(body.get("metrics").isNull()).isTrue();
        assertThat(body.get("modelUnavailableReason").asString()).isNotBlank();
        assertThat(body.get("policyVersion").asString()).isNotBlank();
    }

    @Test
    @DisplayName("every band is published with what happens to a transaction in it")
    void publishesTheWholePolicy() {
        JsonNode thresholds = get("/api/v1/models/active", analystToken, 200).get("thresholds");

        assertThat(thresholds).hasSize(4);

        // Ascending by score, because the bands are declared least to most
        // severe and the projection walks them in that order. A client charting
        // them should not have to sort.
        double previous = -1;
        boolean sawAnAlertingBand = false;
        for (JsonNode threshold : thresholds) {
            double bound = threshold.get("minFinalScore").asDouble();
            assertThat(bound).isGreaterThan(previous);
            previous = bound;

            if (threshold.get("raisesAlert").asBoolean()) {
                sawAnAlertingBand = true;
                // A band that alerts has a priority; one that does not has null
                // rather than a default, because asking the priority of an alert
                // that should not exist is a caller defect.
                assertThat(threshold.get("priority").isNull()).isFalse();
            } else {
                assertThat(threshold.get("priority").isNull()).isTrue();
            }
        }
        assertThat(sawAnAlertingBand).isTrue();
    }

    @Test
    @DisplayName("the caveats travel with the figures")
    void publishesItsLimitations() {
        JsonNode limitations = get("/api/v1/models/active", analystToken, 200).get("limitations");

        // A metric without its caveats invites a conclusion the metric does not
        // support, and a caveat that lives only in a model card is one nobody
        // reading the screen has open.
        assertThat(limitations.size()).isGreaterThanOrEqualTo(4);
        assertThat(limitations.toString()).contains("synthetic");
    }

    @Test
    @DisplayName("an auditor can read the model and the policy")
    void auditorsCanReadTheModel() {
        get("/api/v1/models/active", auditorToken, 200);
    }

    @Test
    @DisplayName("reading the model needs a token")
    void refusesAnAnonymousModelRead() {
        client.get().uri("/api/v1/models/active").exchange().expectStatus().isUnauthorized();
    }

    // ----------------------------------------------------------------------- //
    // System health
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("health is 200 even with a component down, and says which")
    void reportsAnUnhealthyComponent() {
        JsonNode body = get("/api/v1/system/health", analystToken, 200);

        // An endpoint that returned an error status when a dependency is
        // unhealthy could not be told apart from one that is unhealthy itself.
        JsonNode components = body.get("components");
        assertThat(components).hasSize(3);
        assertThat(stateOf(components, "api")).isEqualTo("OPERATIONAL");
        assertThat(stateOf(components, "database"))
                .as("this suite runs against real PostgreSQL, so the check should pass")
                .isEqualTo("OPERATIONAL");
        assertThat(stateOf(components, "scoring"))
                .as("no scoring service is running in this suite")
                .isEqualTo("OUTAGE");
        assertThat(body.get("checkedAt").asString()).isNotBlank();
    }

    @Test
    @DisplayName("every component says something a person could act on")
    void everyComponentCarriesADetail() {
        JsonNode components = get("/api/v1/system/health", analystToken, 200).get("components");

        for (JsonNode component : components) {
            assertThat(component.get("detail").asString()).isNotBlank();
            assertThat(component.get("name").asString()).isNotBlank();
            // No UNKNOWN: a component in this list was asked, and "we did not
            // ask" is not a state this API should report about its own
            // dependencies.
            assertThat(component.get("state").asString()).isIn("OPERATIONAL", "DEGRADED", "OUTAGE");
        }
    }

    @Test
    @DisplayName("nothing in the health body names a host, a port or an exception")
    void leaksNothingOperational() {
        String body = get("/api/v1/system/health", analystToken, 200).toString();

        // The detail is generated here and is never a dependency's own error
        // text. An error response is read by whoever sent the request.
        assertThat(body).doesNotContain("Exception").doesNotContain("localhost").doesNotContain("jdbc:");
    }

    @Test
    @DisplayName("an auditor can read system health")
    void auditorsCanReadHealth() {
        get("/api/v1/system/health", auditorToken, 200);
    }

    @Test
    @DisplayName("reading system health needs a token")
    void refusesAnAnonymousHealthRead() {
        client.get().uri("/api/v1/system/health").exchange().expectStatus().isUnauthorized();
    }

    // ----------------------------------------------------------------------- //
    // Fixtures and reads
    // ----------------------------------------------------------------------- //

    private static String stateOf(JsonNode components, String componentId) {
        for (JsonNode component : components) {
            if (componentId.equals(component.get("componentId").asString())) {
                return component.get("state").asString();
            }
        }
        throw new AssertionError("No component with id " + componentId);
    }

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

    private JsonNode get(String uri, String token, int expected) {
        return MAPPER.readTree(client.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());
    }
}
