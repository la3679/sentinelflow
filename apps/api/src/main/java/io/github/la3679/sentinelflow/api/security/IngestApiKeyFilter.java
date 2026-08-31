/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.la3679.sentinelflow.api.web.ApiHeaders;
import io.github.la3679.sentinelflow.api.web.ProblemWriter;

/**
 * The credential on {@code POST /api/v1/transactions} (ADR-0017 §1).
 *
 * <h2>Why a key and not a token</h2>
 *
 * The caller here is a payment pipeline rather than a person, so an operator's password buys nothing
 * — ADR-0012 §5 said so when it left this endpoint open, and ADR-0017 §1 records why issuing the
 * pipeline an operator token, a client certificate or a signed request was rejected in favour of a
 * shared secret. What the key grants is exactly one thing: the right to post a transaction. It is not
 * a role, it reads nothing, and it does not become an actor in the audit trail.
 *
 * <h2>The comparison is constant-time, and that is not ceremony</h2>
 *
 * {@link String#equals} returns as soon as two bytes differ, so how long it takes is a function of
 * how many leading bytes were right. On a secret compared on every request that is a usable oracle,
 * and {@link MessageDigest#isEqual} costs nothing to use instead. The length check before it is done
 * on the digest rather than the raw value, so an attacker cannot learn the key's length by timing
 * either.
 *
 * <h2>Only this one endpoint</h2>
 *
 * The path and the method are both matched. {@code GET /api/v1/transactions} is an operator reading
 * other people's activity and stays under {@code anyRequest().authenticated()}; the two are not the
 * same permission and must not share a credential.
 *
 * <p>Ordered after the rate limiter deliberately: a caller guessing keys should be counted before
 * they are compared, or the limiter is protecting everything except the endpoint holding a secret.
 */
@Component
@Order(IngestApiKeyFilter.ORDER)
public class IngestApiKeyFilter extends OncePerRequestFilter {

    /** After the size and rate filters, still ahead of the security chain. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 40;

    private static final String INGEST_PATH = "/api/v1/transactions";

    private final byte[] expectedDigest;
    private final ProblemWriter problems;

    public IngestApiKeyFilter(IngestionProperties ingestion, ProblemWriter problems) {
        // The digest rather than the key. Both sides are hashed before they are
        // compared, which makes the comparison fixed-length whatever was sent -
        // so a wrong-length guess and a wrong-value one take the same time.
        this.expectedDigest = sha256(ingestion.apiKeyBytes());
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod()) && INGEST_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String presented = request.getHeader(ApiHeaders.API_KEY);
        if (presented != null
                && MessageDigest.isEqual(expectedDigest, sha256(presented.getBytes(StandardCharsets.UTF_8)))) {
            chain.doFilter(request, response);
            return;
        }

        // Absent and wrong are the same answer, and neither says which. The
        // reasoning is ProblemAccessHandlers': telling a caller which half was
        // wrong helps exactly one kind of caller.
        problems.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "ingestion-key-required",
                "Ingestion credential required",
                "This endpoint requires a valid " + ApiHeaders.API_KEY + " header.");
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256. Rethrown rather than swallowed: a filter
            // that silently stopped comparing would let anything through.
            throw new IllegalStateException("SHA-256 is unavailable, which no supported JVM permits", impossible);
        }
    }
}
