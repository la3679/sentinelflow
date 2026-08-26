/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import java.util.regex.Pattern;

/**
 * Turns an exception into something safe to write to a dead-letter topic.
 *
 * <p>ADR-0006 §4 says a dead-letter record carries an exception type and a sanitised message and
 * never a stack trace, a secret, or an unsanitised payload fragment. The first two are structural —
 * this class only ever sees {@code getMessage()}, and never a cause chain or a frame. The third is
 * not, because an exception message is written by whoever threw it and routinely quotes the value
 * that offended: a driver reporting a constraint violation prints the row, and a parser reporting a
 * bad field prints the field.
 *
 * <p>So three things are removed rather than trusted:
 *
 * <ul>
 *   <li><strong>UUIDs</strong>, which are the identifiers that would let a dead-letter record be
 *       joined back to a customer or an account by anyone who can read the topic. The envelope
 *       already carries the ones an operator legitimately needs, in fields, where they belong.
 *   <li><strong>Long digit runs</strong>, twelve or more. Nothing in this project's own messages is
 *       a long unbroken number; a payment instrument is.
 *   <li><strong>Line structure</strong>, collapsed to single spaces, so a message that arrives
 *       already carrying a formatted block cannot smuggle one in as a "message".
 * </ul>
 *
 * <p>This is a floor and not a proof. It is paired with the rule that no payload in this system ever
 * carries real personal data (§19.4), because sanitisation applied to something that should not have
 * existed is a mitigation, not a fix.
 */
final class FailureSanitiser {

    /** The schema's own bound on {@code sanitisedMessage}. */
    static final int MAX_MESSAGE_LENGTH = 2000;

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{12,}");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private FailureSanitiser() {}

    /** The fully-qualified type name. Never an instance, never a message, never a frame. */
    static String typeOf(Throwable failure) {
        return failure.getClass().getName();
    }

    /**
     * @return the message with identifiers redacted and length bounded; never null, because the
     *     schema requires the field to be present even when the exception carried no message
     */
    static String sanitise(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            // Present and empty rather than absent: the schema requires the
            // field, and "this exception said nothing" is itself the finding.
            return "";
        }

        String redacted = UUID_PATTERN.matcher(message).replaceAll("<uuid>");
        redacted = LONG_DIGIT_RUN.matcher(redacted).replaceAll("<digits>");
        redacted = WHITESPACE_RUN.matcher(redacted).replaceAll(" ").trim();

        if (redacted.length() > MAX_MESSAGE_LENGTH) {
            // One character short of the bound, so the ellipsis fits inside it
            // rather than pushing the field over the schema's maxLength.
            return redacted.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
        }
        return redacted;
    }
}
