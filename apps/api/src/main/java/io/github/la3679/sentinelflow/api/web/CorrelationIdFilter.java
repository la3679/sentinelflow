/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.la3679.sentinelflow.api.domain.UuidV7;

/**
 * Gives every request a correlation identifier, and makes sure it is the same one everywhere.
 *
 * <p>A single identifier ties an API call to its log lines, its outbox row, the Kafka record that
 * row becomes, and the consumer's log lines on the other side. Without it, "what happened to this
 * request" is a question answered by timestamp correlation and guesswork.
 *
 * <p><strong>A client-supplied value is validated as a UUID, or replaced.</strong> This value is
 * written into logs, echoed in a response header, and stored on an outbox row. Echoing arbitrary
 * client text into a log would let a caller inject newlines and forge log entries, and into a header
 * would let it inject header content; neither is a hypothetical, both are cheap to prevent, and the
 * check that prevents them is the same one that keeps the identifier meaningful. A malformed value
 * is silently replaced rather than rejected: the header is optional, a bad one is a client bug that
 * should not fail a legitimate transaction, and the response says which identifier was actually
 * used.
 *
 * <p>Generated identifiers are UUIDv7 like everything else here, so a correlation identifier sorts
 * by the time its request arrived.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    /** The request attribute the rest of the request can read it from. */
    public static final String ATTRIBUTE = "sentinelflow.correlationId";

    /** The MDC key, so every log line in the request carries it without being asked. */
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        UUID correlationId = parseOrGenerate(request.getHeader(HEADER));

        request.setAttribute(ATTRIBUTE, correlationId);
        // Set before the chain runs and cleared in the finally, because the
        // thread goes back into a pool: a value left behind would attach itself
        // to an unrelated request's logs.
        MDC.put(MDC_KEY, correlationId.toString());
        // Set on the response before the chain rather than after, so it is
        // present even on a response the chain commits itself - an error page,
        // a streamed body - where a header written afterwards is discarded.
        response.setHeader(HEADER, correlationId.toString());

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static UUID parseOrGenerate(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UuidV7.randomUuid();
        }
        try {
            return UUID.fromString(supplied.trim());
        } catch (IllegalArgumentException notAUuid) {
            // Deliberately not logged at warn: a caller could otherwise fill the
            // log by sending malformed headers, which is the same class of
            // problem this method exists to prevent.
            return UuidV7.randomUuid();
        }
    }

    /** The identifier for the request being handled, for a component that cannot see the request. */
    public static UUID currentOrNew(HttpServletRequest request) {
        Object attribute = request.getAttribute(ATTRIBUTE);
        return attribute instanceof UUID correlationId ? correlationId : UuidV7.randomUuid();
    }
}
