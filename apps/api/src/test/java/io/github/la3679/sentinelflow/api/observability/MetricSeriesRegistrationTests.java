/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.alert.AlertService;
import io.github.la3679.sentinelflow.api.alert.AlertTransitions;
import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.IngestionSource;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.messaging.consumer.IdempotentEventProcessor;
import io.github.la3679.sentinelflow.api.messaging.consumer.TransactionCreatedConsumer;
import io.github.la3679.sentinelflow.api.risk.AlertRaiser;
import io.github.la3679.sentinelflow.api.risk.RiskPolicyProperties;
import io.github.la3679.sentinelflow.api.service.TransactionIngestionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * That every series a dashboard reads exists before anything has happened.
 *
 * <h2>Why this is worth a test at all</h2>
 *
 * Micrometer creates a series on its first increment. Before the first conflicted idempotency key,
 * {@code sentinelflow_transactions_ingested_total{outcome="conflict"}} does not exist — and in
 * Prometheus an absent series and a zero look identical on a graph and completely different in an
 * alert rule. A rule reading "conflicts above zero" never fires on a service that has never had one,
 * which is exactly the service the rule was written for.
 *
 * <p>The rarest outcomes are the ones worth alerting on, so they are precisely the ones that would
 * not exist when the rule was written. That is the trap this closes.
 *
 * <p><strong>Found by a dashboard, not by a test.</strong> Every panel query was run against the
 * running stack and eight came back with no series at all. Five of those were these counters. The
 * scoring service had already made this decision for its own three collectors in Phase 4, and
 * {@code OutboxBatchProcessor} for its two; this is the same decision applied everywhere else.
 *
 * <h2>Constructed with nulls</h2>
 *
 * Each service is built with null collaborators and a real registry. Nothing here calls a method
 * that would touch them: the subject is what the <em>constructor</em> registers, and supplying real
 * repositories would make this a slower test of the same one line.
 */
class MetricSeriesRegistrationTests {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    @DisplayName("all nine ingestion series exist at zero before a single transaction arrives")
    void ingestionSeriesExistUpFront() {
        new TransactionIngestionService(null, meters);

        List<Counter> counters =
                List.copyOf(meters.find("sentinelflow.transactions.ingested").counters());

        assertThat(counters).hasSize(IngestionSource.values().length * 3);
        assertThat(counters).allSatisfy(counter -> assertThat(counter.count()).isZero());

        for (IngestionSource source : IngestionSource.values()) {
            for (String outcome : List.of("created", "replayed", "conflict")) {
                assertThat(meters.find("sentinelflow.transactions.ingested")
                                .tag("source", source.name())
                                .tag("outcome", outcome)
                                .counter())
                        .as("no series for %s/%s", source, outcome)
                        .isNotNull();
            }
        }
    }

    @Test
    @DisplayName("only the bands that alert get an alert series, and both do")
    void alertSeriesCoverTheAlertingBandsAndNoOthers() {
        new AlertRaiser(null, null, null, null, policy(), null, meters, null);

        assertThat(meters.find("sentinelflow.alerts.raised").counters())
                .as("a band below the threshold cannot raise an alert, so a series for it would be "
                        + "a line that is permanently zero for a structural reason")
                .hasSize(2);

        assertThat(meters.find("sentinelflow.alerts.raised")
                        .tag("band", RiskBand.HIGH.name())
                        .tag("priority", AlertPriority.HIGH.name())
                        .counter())
                .isNotNull();
        assertThat(meters.find("sentinelflow.alerts.raised")
                        .tag("band", RiskBand.CRITICAL.name())
                        .tag("priority", AlertPriority.URGENT.name())
                        .counter())
                .isNotNull();
        assertThat(meters.find("sentinelflow.alerts.raised")
                        .tag("band", RiskBand.LOW.name())
                        .counter())
                .as("LOW does not alert")
                .isNull();
    }

    @Test
    @DisplayName("every legal transition has a series, and no illegal pair does")
    void transitionSeriesComeFromTheTransitionTable() {
        new AlertService(null, null, null, null, null, null, meters, null);

        long legal = 0;
        for (AlertStatus from : AlertStatus.values()) {
            for (AlertStatus to : AlertTransitions.legalTargetsFrom(from)) {
                legal++;
                assertThat(meters.find("sentinelflow.alerts.transitions")
                                .tag("from", from.name())
                                .tag("to", to.name())
                                .counter())
                        .as("no series for %s -> %s", from, to)
                        .isNotNull();
            }
        }

        assertThat(meters.find("sentinelflow.alerts.transitions").counters())
                .as("the count comes from AlertTransitions' own table, so a transition added there "
                        + "appears here without anybody remembering to add it - and an illegal pair "
                        + "can never be registered")
                .hasSize((int) legal);

        assertThat(meters.find("sentinelflow.alerts.transitions")
                        .tag("from", AlertStatus.CLOSED.name())
                        .counter())
                .as("nothing moves out of CLOSED")
                .isNull();
    }

    @Test
    @DisplayName("the consumer's two outcomes exist before it has read anything")
    void consumerSeriesExistUpFront() {
        new IdempotentEventProcessor(null, meters);

        assertThat(meters.find("sentinelflow.consumer.events").counters()).hasSize(2);
        assertThat(meters.find("sentinelflow.consumer.events")
                        .tag("consumer", TransactionCreatedConsumer.CONSUMER_NAME)
                        .tag("outcome", "processed")
                        .counter())
                .as("a stack that has just started otherwise has no series here at all, which on a "
                        + "dashboard is indistinguishable from a consumer that is not running")
                .isNotNull();
        assertThat(meters.find("sentinelflow.consumer.events")
                        .tag("consumer", TransactionCreatedConsumer.CONSUMER_NAME)
                        .tag("outcome", "duplicate")
                        .counter())
                .isNotNull();
    }

    private static RiskPolicyProperties policy() {
        Map<RiskBand, BigDecimal> bounds = new EnumMap<>(RiskBand.class);
        bounds.put(RiskBand.LOW, new BigDecimal("0"));
        bounds.put(RiskBand.MEDIUM, new BigDecimal("40"));
        bounds.put(RiskBand.HIGH, new BigDecimal("70"));
        bounds.put(RiskBand.CRITICAL, new BigDecimal("90"));

        Map<RiskBand, AlertPriority> priorities = new EnumMap<>(RiskBand.class);
        priorities.put(RiskBand.HIGH, AlertPriority.HIGH);
        priorities.put(RiskBand.CRITICAL, AlertPriority.URGENT);

        return new RiskPolicyProperties("1.1.0", new BigDecimal("0.6"), bounds, RiskBand.HIGH, priorities);
    }
}
