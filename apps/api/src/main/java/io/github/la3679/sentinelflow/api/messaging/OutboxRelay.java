/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.domain.OutboxStatus;
import io.github.la3679.sentinelflow.api.persistence.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Polls the outbox and drains what is due.
 *
 * <p><strong>Polling, not logical decoding</strong> (ADR-0005). Debezium is lower-latency and the
 * right answer at a volume this project does not reach; it costs a connector to run, a replication
 * slot whose neglect fills a disk, and a second deployment artifact — and it moves the mechanism out
 * of this repository into a connector's configuration, which is the opposite of what a
 * demonstration of the outbox pattern wants.
 *
 * <p><strong>{@code fixedDelay}, not {@code fixedRate}.</strong> The interval is measured from the
 * end of the previous drain, so a slow broker cannot make drains overlap and pile up behind each
 * other. With {@code fixedRate}, a drain taking longer than the interval schedules the next one
 * immediately and the relay competes with itself for the same rows.
 *
 * <p>The three gauges here are read at scrape time. Depth alone cannot distinguish a busy relay from
 * a stuck one — a queue of constant size is healthy if it is turning over and broken if it is not —
 * so age is registered alongside it, and both are needed rather than one being added after an
 * incident (ADR-0005 §6).
 */
@Component
@ConditionalOnProperty(prefix = "sentinelflow.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxBatchProcessor processor;

    public OutboxRelay(OutboxBatchProcessor processor, OutboxEventRepository outbox, MeterRegistry meters) {
        this.processor = processor;

        Gauge.builder("sentinelflow.outbox.pending", () -> outbox.countByStatus(OutboxStatus.PENDING))
                .description("Events waiting to be published. Rising monotonically means the relay is behind.")
                .register(meters);

        Gauge.builder("sentinelflow.outbox.failed", () -> outbox.countByStatus(OutboxStatus.FAILED))
                .description("Events that gave up. Should be zero; anything else needs an operator.")
                .register(meters);

        Gauge.builder("sentinelflow.outbox.oldest.age.seconds", () -> {
                    Double age = outbox.oldestPendingAgeSeconds();
                    // No pending events is zero lag, not a missing measurement.
                    return age == null ? 0.0d : age;
                })
                .description("Age of the oldest unpublished event. How stale the event stream is.")
                .register(meters);
    }

    @Scheduled(
            fixedDelayString = "${sentinelflow.outbox.poll-interval:500ms}",
            initialDelayString = "${sentinelflow.outbox.poll-interval:500ms}")
    void drain() {
        try {
            int claimed = processor.drainOnce();
            if (claimed > 0) {
                log.debug("Outbox drain published or rescheduled {} event(s)", claimed);
            }
        } catch (RuntimeException e) {
            // The scheduler stops invoking a task that throws. A database blip
            // must not silently retire the relay for the lifetime of the
            // process, so this catches, records, and lets the next tick try
            // again. Nothing below this point is swallowed - individual
            // publication failures are handled inside the batch and never reach
            // here.
            log.error("Outbox drain failed; the next poll will retry", e);
        }
    }
}
