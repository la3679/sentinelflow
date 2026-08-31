/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.limit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
import io.github.la3679.sentinelflow.api.web.limit.RateLimiter.Category;

/**
 * Counts every request under {@code /api/v1/} against its caller's allowance (ADR-0017 §2).
 *
 * <h2>Before authentication, and that ordering is the point</h2>
 *
 * This runs ahead of the security filter chain. Keying on the authenticated principal would read
 * better and would leave the hole that matters: a caller sending a thousand requests a second with no
 * token, or a wrong one, would be refused by the chain and never counted — having already cost a
 * signature verification, or a BCrypt comparison, each time. A limiter exists to bound work done for
 * an unidentified caller, so it has to run before the work that identifies them.
 *
 * <h2>What a caller is</h2>
 *
 * A hash of the {@code X-API-Key} header when one is present, and the remote address otherwise.
 * Hashed rather than stored, because the raw key is a credential and this map is a long-lived
 * in-memory structure; a prefix of the digest is enough to tell two callers apart and is not enough
 * to be one of them.
 *
 * <p><strong>No forwarding header is trusted.</strong> {@code X-Forwarded-For} is a request header
 * like any other and there is no trusted proxy in this stack to make it meaningful. Reading it would
 * let one caller spread itself across as many buckets as it cared to invent, which is a limiter that
 * limits only the honest.
 *
 * <h2>What a refusal says</h2>
 *
 * {@code 429} with {@code Retry-After} in seconds, in the same problem shape as every other error,
 * and nothing about the limit or how much of it is left. {@code X-RateLimit-*} headers are a
 * convenience for a well-behaved client and a progress bar for a badly behaved one.
 */
@Component
@Order(RateLimitFilter.ORDER)
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * After the correlation filter and before the security chain.
     *
     * <p>Correlation first, so a {@code 429} carries the identifier a caller would quote when
     * reporting it; security after, for the reason in this class's documentation.
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

    private static final String PATH_PREFIX = "/api/v1/";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String INGEST_PATH = "/api/v1/transactions";

    /** Half a SHA-256. Enough to separate callers, and not a key anybody can work backwards from. */
    private static final int CALLER_HASH_CHARS = 32;

    private final RateLimiter limiter;
    private final ProblemWriter problems;

    public RateLimitFilter(RateLimiter limiter, ProblemWriter problems) {
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The actuator is not limited. A liveness probe and a scrape run on a
        // schedule that a limit could refuse, and refusing a health check is how
        // a limiter takes a service down rather than protecting it.
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Decision decision = limiter.tryAcquire(categoryOf(request), callerOf(request));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        problems.write(
                request,
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                "rate-limited",
                "Too many requests",
                "This client has made too many requests. Retry after the interval in the Retry-After header.");
    }

    private static Category categoryOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean post = HttpMethod.POST.matches(request.getMethod());
        if (post && LOGIN_PATH.equals(path)) {
            return Category.LOGIN;
        }
        if (post && INGEST_PATH.equals(path)) {
            return Category.INGEST;
        }
        return Category.STANDARD;
    }

    private static String callerOf(HttpServletRequest request) {
        String apiKey = request.getHeader(ApiHeaders.API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + digest(apiKey);
        }
        // getRemoteAddr and nothing else. See the class documentation for why no
        // forwarding header is read.
        String address = request.getRemoteAddr();
        return "addr:" + (address == null ? "unknown" : address);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, CALLER_HASH_CHARS);
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256. Rethrown rather than swallowed, because a
            // limiter that silently stopped telling callers apart would count
            // everybody as one and refuse them all together.
            throw new IllegalStateException("SHA-256 is unavailable, which no supported JVM permits", impossible);
        }
    }
}
