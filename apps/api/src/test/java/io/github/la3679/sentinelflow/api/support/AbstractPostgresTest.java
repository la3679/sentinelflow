/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Base class for every test that needs the real schema.
 *
 * <p>Extending this starts the application against {@link PostgresContainerSupport}, which means
 * Flyway applies every migration to an empty database and Hibernate then validates every mapping
 * against the tables that resulted. Both of those are assertions in themselves: a context that
 * loads is a schema that applies and a set of mappings that agree with it.
 *
 * <p>{@code webEnvironment = NONE} by default - a schema test has no use for a servlet container,
 * and starting one costs a port and a second context. A subclass that does need HTTP re-declares
 * {@code @SpringBootTest} with the web environment it wants; the closer annotation wins.
 *
 * <p><strong>Why the credentials are set here.</strong> {@code application.yaml} reads it
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
            // The signing key has no usable default in application.yaml, for
            // the reason ADR-0012 section 6 gives, and JwtProperties refuses a
            // blank one - so every context needs a value. This one is a
            // throwaway of the right length and is not a secret in any sense
            // that matters: it signs tokens for a container that is destroyed
            // when the fork ends.
            "sentinelflow.security.jwt.secret=" + TestCredentials.JWT_SECRET,
            // Same rule, stronger reason (ADR-0017 section 1): the ingestion key
            // has no default because a default would grant the right to write to
            // the database and the outbox on every deployment that forgot to set
            // one. IngestionProperties refuses a blank, so every context needs a
            // value here whether or not the test posts a transaction.
            "sentinelflow.security.ingestion.api-key=" + TestCredentials.INGEST_API_KEY,
            // The rate limits are raised out of the way for every suite except
            // the one that is about them. A concurrency test posting the same
            // idempotency key from sixteen threads is testing a unique
            // constraint, and it should fail on that or pass, never on an
            // allowance it was never meant to reach. RequestLimitsIT sets its
            // own tight values and is the only place the defaults' behaviour is
            // asserted.
            "sentinelflow.limits.login.permits=100000",
            "sentinelflow.limits.login.burst=100000",
            "sentinelflow.limits.ingest.permits=100000",
            "sentinelflow.limits.ingest.burst=100000",
            "sentinelflow.limits.standard.permits=100000",
            "sentinelflow.limits.standard.burst=100000",
            // No broker here, and a listener container whose bootstrap address
            // does not resolve fails the application context at startup rather
            // than retrying - so every schema test would fail on a Kafka error.
            // A subclass that starts a broker turns it back on; its own
            // @TestPropertySource wins over this one.
            "sentinelflow.consumer.enabled=false",
            // Same reason, one step further along: the broker readings are a
            // scheduled admin call, and a scheduler retrying an address that
            // does not resolve fills an unrelated suite's output with
            // connection warnings every fifteen seconds. A subclass that starts
            // a broker turns it back on.
            "sentinelflow.observability.kafka.enabled=false"
        })
public abstract class AbstractPostgresTest {}
