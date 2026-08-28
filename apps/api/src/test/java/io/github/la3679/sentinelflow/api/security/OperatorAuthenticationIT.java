/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Logging in, and what a token is worth, over real HTTP against the real filter chain.
 *
 * <p>Every property asserted here is a property of the chain rather than of a method: whether an
 * unauthenticated request reaches a handler at all, what shape a refusal has, and whether the token
 * this service issues is one it accepts back. A sliced test with a mocked decoder would answer all
 * three by construction.
 *
 * <p><strong>The operators are inserted here rather than seeded.</strong> One container serves the
 * whole fork, and the seed skips a database that already has parties in it — so a suite relying on
 * it would pass alone and fail behind any other suite that had written a customer first, which is
 * exactly what happened. That the seed gives its operators working credentials is asserted where the
 * seed is, in {@code DeterministicSeedLoaderIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorAuthenticationIT extends AbstractPostgresTest {

    /** The password these operators are created with, in this suite and nowhere else. */
    private static final String PASSWORD = "a-password-for-a-test-only";

    /** Unique per run, because the users table outlives this suite in a shared container. */
    private static final String SUFFIX = Long.toString(System.nanoTime() % 100_000L);

    private static final String ANALYST = "analyst.it" + SUFFIX;
    private static final String ADMINISTRATOR = "administrator.it" + SUFFIX;
    private static final String AUDITOR = "auditor.it" + SUFFIX;

    /** The compose console, and the first entry of the allow-list this suite runs with. */
    private static final String CONSOLE_ORIGIN = "http://localhost:5173";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private RestTestClient client;

    @BeforeEach
    void bindToRunningServer() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        createOperatorOnce(ANALYST, RoleCode.ANALYST);
        createOperatorOnce(ADMINISTRATOR, RoleCode.ADMINISTRATOR);
        createOperatorOnce(AUDITOR, RoleCode.AUDITOR);
    }

    /**
     * One operator with one role and one credential, created the way the seed creates them.
     *
     * <p>The hash goes through the application's own {@link PasswordEncoder}, not a literal: a
     * hard-coded hash would be a published credential, and one produced by a different encoder than
     * the login path uses would make this suite prove nothing about the login path.
     */
    private void createOperatorOnce(String username, RoleCode role) {
        Integer existing =
                jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username);
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO users (username, display_name, status) VALUES (?, ?, 'ACTIVE')",
                username,
                "Integration test " + role);
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.id, r.id FROM users u, roles r WHERE u.username = ? AND r.code = ?
                """, username, role.name());
        jdbc.update("""
                INSERT INTO user_credentials (user_id, password_hash)
                SELECT u.id, ? FROM users u WHERE u.username = ?
                """, passwordEncoder.encode(PASSWORD), username);
    }

    // ----------------------------------------------------------------------- //
    // Logging in
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a seeded operator exchanges their password for a token this service accepts back")
    void logsIn() {
        String body = login(ANALYST, PASSWORD, 200);

        JsonNode response = MAPPER.readTree(body);
        assertThat(response.get("tokenType").asString())
                .as("sent rather than assumed: it is what the client puts before the value in the "
                        + "Authorization header")
                .isEqualTo("Bearer");
        assertThat(response.get("expiresAt").asString())
                .as("beside the token, so a client need not decode one to know when to log in again")
                .isNotBlank();
        assertThat(response.get("roles").valueStream().map(JsonNode::asString).toList())
                .as("beside the token for the same reason, so a console can decide which controls "
                        + "to offer without reading a structure this service is free to change")
                .containsExactly("ANALYST");

        // Decoded with the application's own decoder, which is the assertion
        // that matters: a token this service signs and cannot verify would be
        // a configuration defect no unit test of the issuer could find.
        Jwt token = decoder.decode(response.get("token").asString());
        assertThat(token.getSubject())
                .as("the user's identifier, because every audit row this token leads to writes it "
                        + "as a foreign key")
                .isNotBlank();
        assertThat(token.getClaimAsStringList("roles")).containsExactly("ANALYST");
        assertThat(token.getIssuedAt()).isNotNull();
        assertThat(token.getExpiresAt()).isAfter(token.getIssuedAt());
    }

    @Test
    @DisplayName("an administrator's token carries the role that lets them do more")
    void carriesTheRoleHeld() {
        Jwt token = decoder.decode(MAPPER.readTree(login(ADMINISTRATOR, PASSWORD, 200))
                .get("token")
                .asString());

        assertThat(token.getClaimAsStringList("roles")).containsExactly("ADMINISTRATOR");
    }

    @Test
    @DisplayName("the roles in the response are the roles in the token, not a second reading of them")
    void theResponseAndTheTokenAgreeOnTheRoles() {
        JsonNode response = MAPPER.readTree(login(AUDITOR, PASSWORD, 200));

        // The audit trail records the role from the token; the console offers
        // controls from the response. If the two could disagree, an operator
        // would be shown an action attributed to a capacity they were not
        // exercising, so this asserts they come from one reading.
        assertThat(response.get("roles").valueStream().map(JsonNode::asString).toList())
                .isEqualTo(decoder.decode(response.get("token").asString()).getClaimAsStringList("roles"));
    }

    // ----------------------------------------------------------------------- //
    // Being refused
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a wrong password and an unknown username are refused identically")
    void refusesWithoutSayingWhy() {
        String wrongPassword = login(ANALYST, "not-the-password", 401);
        String noSuchUser = login("nobody.at.all", PASSWORD, 401);

        // Identical but for the correlation identifier, which is per-request
        // by design and is the one field that must differ. A message that named
        // which half was wrong would turn an endpoint that is necessarily open
        // into an oracle for which usernames exist.
        assertThat(withoutCorrelationId(wrongPassword)).isEqualTo(withoutCorrelationId(noSuchUser));

        JsonNode problem = MAPPER.readTree(wrongPassword);
        assertThat(problem.get("status").asInt()).isEqualTo(401);
        assertThat(problem.get("detail").asString()).isEqualTo("The username and password were not accepted.");
    }

    @Test
    @DisplayName("the system principal cannot log in, because it has no credential to log in with")
    void theSystemPrincipalCannotLogIn() {
        // V1 inserts it so automated actions have an actor. ADR-0012 §2 makes
        // "it must never authenticate" structural rather than a rule somebody
        // remembers: there is no row in user_credentials, and the login path
        // cannot find what does not exist.
        login("system", PASSWORD, 401);
    }

    // ----------------------------------------------------------------------- //
    // Carrying the token
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a protected endpoint refuses an anonymous request in the shape every other error has")
    void refusesAnonymousRequests() {
        String body = client.get()
                .uri("/actuator/info")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        JsonNode problem = MAPPER.readTree(body);
        assertThat(problem.get("title").asString())
                .as("RFC 9457, like every other error from this API. A client with one parser for "
                        + "errors should not need a second one for the two a filter produces.")
                .isEqualTo("Authentication required");
    }

    @Test
    @DisplayName("the same endpoint answers when the request carries a token")
    void acceptsATokenItIssued() {
        String token =
                MAPPER.readTree(login(AUDITOR, PASSWORD, 200)).get("token").asString();

        client.get()
                .uri("/actuator/info")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("a token this service did not sign is refused")
    void refusesAForgedToken() {
        // Signed with a different key of the right length. The signature is the
        // whole of what makes a stateless token trustworthy, so this is the one
        // failure that must never be a 200.
        JwtProperties elsewhere = new JwtProperties(
                "a-different-key-that-is-also-long-enough-for-hs256", Duration.ofMinutes(30), "sentinelflow-api");
        String forged = new TokenIssuer(new SecurityConfiguration(elsewhere).jwtEncoder(), elsewhere)
                .issue(UUID.randomUUID(), List.of(RoleCode.ADMINISTRATOR), Instant.now())
                .value();

        client.get()
                .uri("/actuator/info")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    // ----------------------------------------------------------------------- //
    // Being reached from a browser
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a preflight from the console's origin is answered, and names the Authorization header")
    void answersAPreflightFromTheConsole() {
        // The default allow-list, which is what the demo runs on. Without
        // Authorization among the allowed headers every authenticated request
        // from the console fails its preflight while an anonymous one succeeds,
        // which reads like a token defect and is a configuration one.
        client.options()
                .uri("/api/v1/alerts")
                .header(HttpHeaders.ORIGIN, CONSOLE_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", HttpHeaders.AUTHORIZATION)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", CONSOLE_ORIGIN)
                .expectHeader()
                .value("Access-Control-Allow-Headers", headers -> assertThat(headers)
                        .containsIgnoringCase(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("a preflight is answered before authentication, or no unauthenticated client could ever log in")
    void answersAPreflightWithoutAToken() {
        // The login endpoint is the case that matters: a caller with no token
        // has to be able to get one, and a preflight cannot carry the token the
        // request it precedes would.
        client.options()
                .uri("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, CONSOLE_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("an origin that is not on the list is not told it may read the response")
    void refusesAnOriginThatIsNotAllowed() {
        client.options()
                .uri("/api/v1/alerts")
                .header(HttpHeaders.ORIGIN, "https://not-the-console.example")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    @DisplayName("the actuator is not a browser surface and is not offered to one")
    void doesNotOfferTheActuatorToABrowser() {
        // ADR-0013 §6: the rule is registered for /api/v1/** only. A health
        // screen reads this system's state through this API, not by widening
        // this to a management endpoint shaped by Spring Boot rather than by
        // the contract.
        client.options()
                .uri("/actuator/health")
                .header(HttpHeaders.ORIGIN, CONSOLE_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectHeader()
                .doesNotExist("Access-Control-Allow-Origin");
    }

    /**
     * A problem body with the one field that legitimately differs between two responses removed.
     *
     * <p>The correlation identifier is generated per request and ties the response to its log line.
     * Everything else about a refusal has to be identical, which is what this comparison is for.
     */
    private static JsonNode withoutCorrelationId(String body) {
        ObjectNode problem = (ObjectNode) MAPPER.readTree(body);
        problem.remove("correlationId");
        return problem;
    }

    private String login(String username, String password, int expectedStatus) {
        return client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(new LoginBody(username, password)))
                .exchange()
                .expectStatus()
                .isEqualTo(expectedStatus)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    /** The request shape, written out rather than reusing the DTO, so the wire format is what is asserted. */
    private record LoginBody(String username, String password) {}
}
