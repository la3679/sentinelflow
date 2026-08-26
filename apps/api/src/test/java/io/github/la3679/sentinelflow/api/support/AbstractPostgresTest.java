/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Base class for every test that needs the real schema.
 *
 * <p>Extending this starts the application against {@link PostgresContainerSupport}, which means
 * Flyway applies all six migrations to an empty database and Hibernate then validates every mapping
 * against the tables that resulted. Both of those are assertions in themselves: a context that
 * loads is a schema that applies and a set of mappings that agree with it.
 *
 * <p>{@code webEnvironment = NONE} by default - a schema test has no use for a servlet container,
 * and starting one costs a port and a second context. A subclass that does need HTTP re-declares
 * {@code @SpringBootTest} with the web environment it wants; the closer annotation wins.
 *
 * <p><strong>Why {@code POSTGRES_PASSWORD} is set here.</strong> {@code application.yaml} reads it
 * with no default on purpose, so a deployment with the variable missing fails loudly rather than
 * connecting somewhere with a guessable password. That placeholder still has to resolve for the
 * context to bind, and the value is irrelevant: {@code @ServiceConnection} overrides the URL, user
 * and password with the container's own before a connection is ever opened. Giving it a default in
 * {@code application.yaml} instead would weaken production to make a test convenient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresContainerSupport.class)
@TestPropertySource(
        properties = {
            "POSTGRES_PASSWORD=overridden-by-the-container-service-connection",
            // No broker here, and a listener container whose bootstrap address
            // does not resolve fails the application context at startup rather
            // than retrying - so every schema test would fail on a Kafka error.
            // A subclass that starts a broker turns it back on; its own
            // @TestPropertySource wins over this one.
            "sentinelflow.consumer.enabled=false"
        })
public abstract class AbstractPostgresTest {}
