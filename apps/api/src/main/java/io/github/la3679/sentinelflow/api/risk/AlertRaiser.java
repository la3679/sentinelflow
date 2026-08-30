/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.la3679.sentinelflow.api.domain.ActorRole;
import io.github.la3679.sentinelflow.api.domain.AlertActionType;
import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.domain.ReasonCode;
import io.github.la3679.sentinelflow.api.messaging.payload.AlertCreatedPayload;
import io.github.la3679.sentinelflow.api.observability.CurrentTrace;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;
import io.github.la3679.sentinelflow.api.persistence.entity.AlertAction;
import io.github.la3679.sentinelflow.api.persistence.entity.OutboxEvent;
import io.github.la3679.sentinelflow.api.persistence.entity.RiskAssessment;
import io.github.la3679.sentinelflow.api.persistence.entity.TransactionRecord;
import io.github.la3679.sentinelflow.api.persistence.repository.AlertActionRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.AlertRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.OutboxEventRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Opens an alert for an assessment that banded high enough to be worth a person's time.
 *
 * <h2>Three rows, in the assessment's own transaction</h2>
 *
 * The alert, its first {@code alert_actions} row, and the {@code alert.created} outbox row are
 * written alongside the assessment that caused them. An alert that existed without the assessment it
 * cites, or a queue that showed an alert nobody was told about, are both states this makes
 * unrepresentable rather than unlikely.
 *
 * <h2>Why the alert is not decided here</h2>
 *
 * {@link RiskPolicyProperties} decides. ADR-0008 §4 makes the alerting threshold a versioned
 * business decision on its own schedule, and this class is the mechanism that acts on it — so
 * "should there be an alert" is answered by configuration that an assessment records the version of,
 * and never by a condition written into this file.
 *
 * <p><strong>The band decides, not the score.</strong> The band is what the assessment persists and
 * what an analyst is shown; raising an alert directly off a score would let an alert exist that the
 * band table beside it would have placed differently, and nobody could reconcile the two.
 *
 * <h2>What the summary may say</h2>
 *
 * {@code alerts.summary} is an analyst's first line about why the alert exists, and the schema says
 * it never contains raw payload data. So it is built from the band, the score, the transaction's
 * reference and the leading reason <em>code</em> — never a reason's generated description, which
 * legitimately names a device handle or an amount ratio for an analyst who has already opened the
 * alert. A queue view is a wider audience than an alert detail page.
 */
@Service
public class AlertRaiser {

    private static final Logger log = LoggerFactory.getLogger(AlertRaiser.class);

    /** The schema version of the payload this writes. Bumped only alongside a v2 payload schema. */
    private static final int ALERT_CREATED_SCHEMA_VERSION = 1;

    /** {@code alerts.summary} is {@code varchar(500)}; the format below cannot approach it, and this proves it. */
    static final int MAX_SUMMARY_LENGTH = 500;

    private final AlertRepository alerts;
    private final AlertActionRepository actions;
    private final OutboxEventRepository outbox;
    private final UserRepository users;
    private final RiskPolicyProperties policy;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;
    private final CurrentTrace currentTrace;

    public AlertRaiser(
            AlertRepository alerts,
            AlertActionRepository actions,
            OutboxEventRepository outbox,
            UserRepository users,
            RiskPolicyProperties policy,
            ObjectMapper objectMapper,
            MeterRegistry meters,
            CurrentTrace currentTrace) {
        this.currentTrace = currentTrace;
        this.alerts = alerts;
        this.actions = actions;
        this.outbox = outbox;
        this.users = users;
        this.policy = policy;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    /**
     * Opens the alert, its first history row and its event.
     *
     * <p>No {@code @Transactional} of its own, deliberately. It is called from inside
     * {@link RiskAssessmentService#assess}, which is called from inside
     * {@code IdempotentEventProcessor}'s transaction; a {@code REQUIRES_NEW} here would let an alert
     * commit for an assessment that then rolled back, which is the one outcome the schema's foreign
     * key would refuse and the one nobody would see coming.
     *
     * @param assessment the decision this alert cites. Already constructed, not yet flushed — its
     *     identifier is assigned in the constructor (ADR-0007), so the foreign key is available now.
     * @param transaction the transaction being assessed, for its reference and its account
     * @param at the assessment's own instant, so the alert and the assessment agree about when the
     *     decision was made
     */
    public Alert raise(RiskAssessment assessment, TransactionRecord transaction, UUID correlationId, Instant at) {
        AlertPriority priority = policy.priorityFor(assessment.getRiskBand());
        ReasonCode topReason = AlertCreatedPayload.firstReasonOf(assessment.getReasonCodes());

        Alert alert = new Alert(
                alerts.nextAlertReference(),
                transaction.getId(),
                assessment.getId(),
                priority,
                summaryFor(assessment, transaction, topReason),
                assessment.getRiskBand(),
                assessment.getFinalScore());
        alerts.save(alert);

        // The system principal, because nothing else did this. actor_id is NOT
        // NULL precisely so an unattributable change to a reviewed decision is
        // not representable (V1), and "the pipeline raised it" is an answer
        // where a null would be the absence of one.
        actions.save(AlertAction.of(
                alert.getId(),
                systemPrincipalId(),
                ActorRole.SYSTEM,
                AlertActionType.CREATED,
                "Raised automatically by risk policy " + policy.version() + " from a " + assessment.getRiskBand()
                        + "-band assessment.",
                correlationId));

        outbox.save(outboxEventFor(alert, transaction.getAccountId(), topReason, correlationId, at));

        count(alert);
        log.debug(
                "Raised {} at {} priority for transaction {} ({} band, score {})",
                alert.getAlertReference(),
                priority,
                transaction.getTransactionReference(),
                alert.getRiskBand(),
                alert.getFinalScore());
        return alert;
    }

    /**
     * The analyst's first line, built from what a queue may safely show.
     *
     * <p>The reason <strong>code</strong> and never its description. A rule's generated description
     * legitimately names the device handle it did not recognise or the ratio it measured, which is
     * right on a detail page an analyst has opened and wrong on a queue row and in an event that
     * leaves this service.
     */
    private static String summaryFor(RiskAssessment assessment, TransactionRecord transaction, ReasonCode topReason) {
        String summary = "Transaction %s scored %s and banded %s. Leading factor: %s (%s). Synthetic data."
                .formatted(
                        transaction.getTransactionReference(),
                        assessment.getFinalScore().toPlainString(),
                        assessment.getRiskBand(),
                        topReason.code(),
                        topReason.source());

        // The format above is bounded by construction: a reference is 10
        // characters, a score 6, a band 8 and a code 64. Asserting it anyway,
        // because the column would reject a longer one at commit - inside a
        // Kafka consumer, on a record that would fail identically for ever.
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalStateException(
                    "An alert summary of " + summary.length() + " characters exceeds the column's " + MAX_SUMMARY_LENGTH
                            + ". The format changed without its bound being rechecked.");
        }
        return summary;
    }

    private UUID systemPrincipalId() {
        return users.findSystemPrincipalId()
                .orElseThrow(() -> new IllegalStateException(
                        "The system principal is absent. V1 inserts it as reference data, so a database without "
                                + "it is not one these migrations produced, and no automated action can be "
                                + "attributed in it."));
    }

    private OutboxEvent outboxEventFor(
            Alert alert, UUID accountId, ReasonCode topReason, UUID correlationId, Instant at) {
        return new OutboxEvent(
                EventType.ALERT_CREATED,
                alert.getId(),
                ALERT_CREATED_SCHEMA_VERSION,
                // Keyed by the alert's identifier, not by the account and not
                // by the reference (ADR-0006 section 3). An alert's own
                // transitions must be ordered with respect to each other and
                // have no ordering relationship with any other alert; keying by
                // account would order it against transactions it has nothing to
                // say about.
                //
                // The identifier rather than ALT-0007, which is the one place
                // this differs from the transaction topics. Those key by account
                // *reference* because the reference is what identifies an
                // account to a person and to every consumer; an alert reference
                // is allocated by a sequence that can be exhausted and is
                // deliberately not a foreign key anywhere, so keying on it would
                // tie partitioning to a handle the design says is display-only.
                alert.getId().toString(),
                serialise(AlertCreatedPayload.of(alert, accountId, topReason, at)),
                correlationId,
                // The trace this row came from, so the consumer's work hangs
                // off the request that caused it rather than off the relay that
                // happened to publish it (V11). Absent outside a trace, which
                // is what the seed and any scheduled path get.
                currentTrace.stamp(),
                at);
    }

    private String serialise(AlertCreatedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise an alert.created payload", e);
        }
    }

    /**
     * One counter per alert raised, tagged by priority.
     *
     * <p>Alert volume against review capacity is the number ADR-0011 says the band thresholds should
     * be revisited against, and it cannot be revisited against a figure nobody records.
     */
    private void count(Alert alert) {
        Counter.builder("sentinelflow.alerts.raised")
                .tag("priority", alert.getPriority().name())
                .tag("band", alert.getRiskBand().name())
                .description("Alerts opened by the risk policy, by queue priority and risk band")
                .register(meters)
                .increment();
    }
}
