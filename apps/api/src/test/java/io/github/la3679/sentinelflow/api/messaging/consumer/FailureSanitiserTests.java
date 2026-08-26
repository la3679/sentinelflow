/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a dead-letter record is allowed to say about a failure.
 *
 * <p>These assertions are the enforcement of a privacy rule, not a formatting preference. ADR-0006
 * §4 puts the constraint on the record; this class is where the constraint is actually true or not,
 * because an exception message is written by whoever threw it and routinely quotes the value that
 * offended.
 */
class FailureSanitiserTests {

    @Test
    @DisplayName("identifiers are redacted, so a dead-letter record cannot be joined back to a customer")
    void redactsIdentifiers() {
        UUID accountId = UUID.fromString("01936b2a-7c4f-7000-8000-2f9c1d4e5a6b");
        Exception failure = new IllegalStateException("No account " + accountId + " for card 4111111111111111");

        String sanitised = FailureSanitiser.sanitise(failure);

        assertThat(sanitised).doesNotContain(accountId.toString()).doesNotContain("4111111111111111");
        assertThat(sanitised).isEqualTo("No account <uuid> for card <digits>");
    }

    @Test
    @DisplayName("a short number is left alone, because most numbers in a message are not identifiers")
    void keepsShortNumbers() {
        // An amount, a count, a status code and a port are all numbers worth
        // reading. Redacting every digit would leave messages that say nothing.
        assertThat(FailureSanitiser.sanitise(new IllegalStateException("scoring returned 503 after 2 attempts")))
                .isEqualTo("scoring returned 503 after 2 attempts");
    }

    @Test
    @DisplayName("line structure is collapsed, so nothing can smuggle a block in as a message")
    void collapsesLineStructure() {
        Exception failure = new IllegalStateException("first line\n\tsecond line\r\n   third");

        assertThat(FailureSanitiser.sanitise(failure)).isEqualTo("first line second line third");
    }

    @Test
    @DisplayName("the message is bounded by the schema's own maxLength")
    void boundsLength() {
        String sanitised = FailureSanitiser.sanitise(new IllegalStateException("x".repeat(5_000)));

        assertThat(sanitised).hasSize(FailureSanitiser.MAX_MESSAGE_LENGTH).endsWith("…");
    }

    @Test
    @DisplayName("an exception with no message gives an empty string, because the schema requires the field")
    void nullMessageBecomesEmpty() {
        assertThat(FailureSanitiser.sanitise(new IllegalStateException())).isEmpty();
        assertThat(FailureSanitiser.sanitise(new IllegalStateException("   "))).isEmpty();
    }

    @Test
    @DisplayName("the type is the fully-qualified name, and nothing else about the exception")
    void reportsOnlyTheType() {
        Exception cause = new IllegalArgumentException("the cause, which must not appear");
        Exception failure = new NonRetryableEventException(
                io.github.la3679.sentinelflow.api.domain.DlqFailureClass.MALFORMED_PAYLOAD, "outer", cause);

        assertThat(FailureSanitiser.typeOf(failure))
                .isEqualTo("io.github.la3679.sentinelflow.api.messaging.consumer.NonRetryableEventException");
        // The cause chain is structurally unreachable: only getMessage() is ever
        // read. Asserted so a later "helpful" change to include it fails here.
        assertThat(FailureSanitiser.sanitise(failure)).isEqualTo("outer");
    }
}
