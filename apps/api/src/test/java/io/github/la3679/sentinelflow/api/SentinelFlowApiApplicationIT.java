/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;

/**
 * Smoke test for the service as it is actually served.
 *
 * <p>This starts the real servlet container on a random port and speaks HTTP to it over the
 * network, rather than asserting against a sliced or mocked context. What is worth knowing at this
 * stage is whether the process an operator deploys answers the probes an operator relies on.
 *
 * <p>{@code RestTestClient} is the Spring Framework 7 client for this; {@code TestRestTemplate} was
 * removed in Spring Boot 4.
 *
 * <p><strong>An IT, not a unit test, since Phase 2.</strong> The service now owns a schema, so it
 * cannot start without PostgreSQL to migrate and validate against, and a test that needs a
 * container belongs to Failsafe by this module's own rule. That is not a downgrade: readiness now
 * means the database is reachable and the mappings match it, which is what an operator actually
 * relies on that probe to mean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SentinelFlowApiApplicationIT extends AbstractPostgresTest {

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void bindToRunningServer() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    @DisplayName("the application context loads and the server is listening")
    void contextLoads() {
        assertThat(port).isPositive();
    }

    @Test
    @DisplayName("the health endpoint reports UP")
    void healthEndpointReportsUp() {
        String body = client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("liveness and readiness are exposed as separate probes")
    void probesAreExposedSeparately() {
        // A process that is up but cannot reach its dependencies is live and not
        // ready. Collapsing the two into one endpoint loses that distinction and
        // makes an orchestrator restart a container that only needed to be
        // taken out of rotation.
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("Prometheus metrics are exposed and tagged with the application name")
    void prometheusEndpointIsExposed() {
        String body = client.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("application=\"sentinelflow-api\"");
    }

    @Test
    @DisplayName("management endpoints that were not opened stay closed")
    void unexposedManagementEndpointsAreNotReachable() {
        // env and beans disclose configuration and wiring. Neither is in the
        // exposure list, and neither is permitted by the filter chain.
        //
        // 401 rather than the 404 this asserted before ADR-0012. The filter
        // chain refuses an unauthenticated request before the actuator can
        // decide whether the endpoint exists, so the answer no longer
        // distinguishes "not exposed" from "exposed and not yours" - which is
        // the better of the two answers to give a caller who has not
        // authenticated. What matters is that neither serves, and neither does.
        client.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/beans").exchange().expectStatus().isUnauthorized();
    }
}
