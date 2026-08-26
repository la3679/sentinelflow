/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.domain.EventType;
import io.github.la3679.sentinelflow.api.messaging.EventTopics;

/**
 * The two constants on the consumer that nothing else can check.
 *
 * <p>An annotation attribute must be a constant expression, so the topic cannot be read from
 * {@link EventTopics} at the point it is used, and the consumer name is written into a column with a
 * format constraint the compiler knows nothing about. Both are the kind of duplication that stays
 * correct until the day it does not, and a test is the only thing that holds either together.
 */
class TransactionCreatedConsumerTests {

    /** {@code processed_events_consumer_format}, copied from V6 so a mismatch fails here first. */
    private static final Pattern LEDGER_CONSUMER_FORMAT = Pattern.compile("^[a-z][a-z0-9.-]{2,63}$");

    @Test
    @DisplayName("the listener subscribes to the topic the relay publishes transaction.created to")
    void subscribesToTheRelaysTopic() {
        // If these diverge the application still starts, the relay still
        // publishes, and the consumer sits on an empty topic for ever - a
        // failure with no error anywhere.
        assertThat(TransactionCreatedConsumer.TOPIC).isEqualTo(EventTopics.topicFor(EventType.TRANSACTION_CREATED));
    }

    @Test
    @DisplayName("the consumer name is one the idempotency ledger will accept")
    void consumerNameSatisfiesTheLedgerConstraint() {
        // A name the CHECK rejects would fail on the first insert of the first
        // event, inside a listener, in whichever environment ran first.
        assertThat(TransactionCreatedConsumer.CONSUMER_NAME).matches(LEDGER_CONSUMER_FORMAT);
    }
}
