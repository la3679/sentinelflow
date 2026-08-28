/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.github.la3679.sentinelflow.api.resilience.CircuitBreaker;
import io.github.la3679.sentinelflow.api.scoring.client.ScoringClient;
import io.github.la3679.sentinelflow.api.scoring.client.ScoringUnavailableException;
import io.github.la3679.sentinelflow.api.web.dto.SystemHealthResponse;

/**
 * Whether this service's dependencies are answering.
 *
 * <p>ADR-0014 §2. Composed here rather than read from the actuator, and covering only what this
 * service can observe today: itself, PostgreSQL, and the scoring service. Kafka consumer lag and
 * dead-letter depth are Phase 7's, and are exactly the figures the console used to fabricate.
 *
 * <p><strong>Each component is asked, not inferred.</strong> A health screen assembled from cached
 * state reports what was true when something last happened to notice, which on a quiet system is
 * arbitrarily old — and a stale "operational" is worse than no screen, because somebody acts on it.
 */
@Service
public class SystemHealthService {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);

    private static final String OPERATIONAL = "OPERATIONAL";
    private static final String DEGRADED = "DEGRADED";
    private static final String OUTAGE = "OUTAGE";

    private final JdbcTemplate jdbc;
    private final ScoringClient scoring;

    public SystemHealthService(DataSource dataSource, ScoringClient scoring) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.scoring = scoring;
    }

    public SystemHealthResponse current(UUID correlationId) {
        List<SystemHealthResponse.Component> components = new ArrayList<>();

        // The API answered this request, which is the whole of what it can
        // honestly say about itself. Reporting anything more here would be a
        // process asking itself whether it is well.
        components.add(new SystemHealthResponse.Component(
                "api", "Operations API", OPERATIONAL, "Answering requests, which is how you are reading this."));

        components.add(database());
        components.add(scoringService(correlationId));

        return new SystemHealthResponse(List.copyOf(components), Instant.now());
    }

    /**
     * PostgreSQL, asked rather than assumed.
     *
     * <p>{@code SELECT 1} through the pool: it exercises borrowing a connection, which is the failure
     * an operator actually meets — a database that is up behind an exhausted pool is not one this
     * service can use, and a check that only pinged the host would call that healthy.
     */
    private SystemHealthResponse.Component database() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return new SystemHealthResponse.Component(
                    "database", "PostgreSQL", OPERATIONAL, "A connection was borrowed and a query answered.");
        } catch (RuntimeException failure) {
            log.warn("The database health check failed", failure);
            return new SystemHealthResponse.Component(
                    "database",
                    "PostgreSQL",
                    OUTAGE,
                    "No connection could be borrowed, or the query did not answer. Alerts and"
                            + " transactions will not load.");
        }
    }

    /**
     * The scoring service, and the breaker in front of it.
     *
     * <p>Two different questions, and the answer says which. An open breaker is <em>degraded</em>
     * even if the service would now answer: no assessment is calling it, so every one of them is
     * being decided by the rules alone, which is a thing an operator needs to know and would not
     * learn from a successful probe.
     */
    private SystemHealthResponse.Component scoringService(UUID correlationId) {
        CircuitBreaker.State breaker = scoring.circuitState();
        try {
            scoring.modelInfo(correlationId);
            if (breaker == CircuitBreaker.State.OPEN) {
                return new SystemHealthResponse.Component(
                        "scoring",
                        "Scoring service",
                        DEGRADED,
                        "Answering again, but the circuit breaker is still open: assessments are being"
                                + " decided by the rules alone until it closes.");
            }
            return new SystemHealthResponse.Component(
                    "scoring", "Scoring service", OPERATIONAL, "Answering, with a model loaded.");
        } catch (ScoringUnavailableException unavailable) {
            return new SystemHealthResponse.Component(
                    "scoring",
                    "Scoring service",
                    OUTAGE,
                    "Not answering. Assessments continue on the rules alone and are marked degraded;"
                            + " nothing is lost.");
        }
    }
}
