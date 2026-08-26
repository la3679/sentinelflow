/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one PostgreSQL container every database-backed test in this module runs against.
 *
 * <p><strong>Real PostgreSQL, never H2.</strong> This schema uses {@code uuidv7()}, which arrived
 * in PostgreSQL 18; partial and expression indexes; {@code jsonb} with {@code jsonb_typeof} checks;
 * and regular-expression {@code CHECK} constraints. H2 supports none of that faithfully, so a green
 * H2 run against a schema PostgreSQL would reject is worse than no test at all (ADR-0007).
 *
 * <p><strong>One container for the whole JVM fork.</strong> The container is a static singleton
 * started once and never stopped: Ryuk removes it when the fork exits. Spring's test-context cache
 * keys on configuration, so a suite that mixes {@code @SpringBootTest} web environments would
 * otherwise pay a fresh PostgreSQL start for each distinct context. {@code start()} is guarded
 * inside Testcontainers, so exposing the same instance as a bean in several contexts starts it
 * exactly once.
 *
 * <p><strong>{@code PostgreSQLContainer} is not generic here.</strong> Testcontainers 2.x moved it
 * to {@code org.testcontainers.postgresql} and dropped the self-referential type parameter the 1.x
 * class carried for its fluent builder. The {@code <?>} every 1.x example writes does not compile
 * against this artifact.
 *
 * <p><strong>The image name comes from the pom.</strong> {@code postgres.test.image} is handed down
 * as a system property by both Surefire and Failsafe, so the tested PostgreSQL version is written
 * in exactly one place and cannot drift from {@code compose.yaml} unnoticed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerSupport {

    /**
     * Falls back to the compose version rather than to {@code latest}. A test that silently ran on
     * a different major version than production would still be green, which is the failure this
     * default exists to prevent.
     */
    private static final String IMAGE = System.getProperty("sentinelflow.test.postgres.image", "postgres:18.6-alpine");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("sentinelflow")
            .withUsername("sentinelflow")
            .withPassword("sentinelflow-test");

    static {
        POSTGRES.start();
    }

    /**
     * {@code @ServiceConnection} supplies the JDBC URL, user and password to Spring Boot directly,
     * so no test has to restate them as properties and no test can accidentally point at a
     * developer's local database.
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }
}
