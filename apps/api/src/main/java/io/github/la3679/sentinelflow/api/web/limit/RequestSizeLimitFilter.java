/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.limit;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.la3679.sentinelflow.api.web.ProblemWriter;

/**
 * Bounds how large a request body may be under {@code /api/v1/} (ADR-0017 §3).
 *
 * <h2>Both halves are checked, and either alone would be a hole</h2>
 *
 * A declared {@code Content-Length} above the cap is refused before a byte is read, which is the
 * cheap case and the common one. But {@code Content-Length} is a claim the client makes: a chunked
 * request declares nothing at all, and a lying one declares whatever it likes. So the stream is
 * wrapped as well, and cut off at the same number wherever the bytes actually stop. Trusting the
 * header alone would be a bound a caller opts into.
 *
 * <h2>Why not a Tomcat property</h2>
 *
 * {@code server.tomcat.max-http-form-post-size} applies to form encoding, and nothing here posts a
 * form. Setting it would leave a reader reasonably concluding that JSON was covered, which it is not
 * — a wrong belief about a limit is worse than a missing one, because nobody looks for it again.
 *
 * <h2>Where the refusal happens</h2>
 *
 * The declared-length case answers {@code 413} from this filter. The streamed case cannot: the body
 * is being read by the JSON parser inside the dispatcher by then, so the overrun surfaces carrying
 * {@link RequestTooLargeException} as its cause, and {@code ApiExceptionHandler} maps it to the same
 * {@code 413} and the same problem type. One status, one type, two paths — because a caller should
 * not be able to tell which half caught them.
 */
@Component
@Order(RequestSizeLimitFilter.ORDER)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** After the correlation filter, before the rate limiter: a body is not read to be counted. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    public static final String PROBLEM_TYPE = "request-too-large";

    private static final String PATH_PREFIX = "/api/v1/";

    private final RequestLimitProperties limits;
    private final ProblemWriter problems;

    public RequestSizeLimitFilter(RequestLimitProperties limits, ProblemWriter problems) {
        this.limits = limits;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long maximum = limits.maxRequestBytes();
        if (request.getContentLengthLong() > maximum) {
            problems.write(
                    request,
                    response,
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    PROBLEM_TYPE,
                    "Request body too large",
                    "The request body exceeds the " + maximum + " byte maximum this API accepts.");
            return;
        }

        chain.doFilter(new BoundedRequest(request, maximum), response);
    }

    /** Wraps the body so the bytes actually delivered are counted, whatever the header claimed. */
    private static final class BoundedRequest extends HttpServletRequestWrapper {

        private final long maximum;

        private BoundedRequest(HttpServletRequest request, long maximum) {
            super(request);
            this.maximum = maximum;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new BoundedStream(super.getInputStream(), maximum);
        }
    }

    /**
     * A servlet input stream that refuses to yield more than {@code maximum} bytes.
     *
     * <p>Both {@code read} overloads are overridden. The single-byte one is what a naive reader uses
     * and the array one is what a JSON parser uses, and overriding only the first would leave the
     * limit unenforced on the path that matters — the inherited array implementation does not go
     * through it.
     */
    private static final class BoundedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maximum;
        private long read;

        private BoundedStream(ServletInputStream delegate, long maximum) {
            this.delegate = delegate;
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws IOException {
            read += bytes;
            if (read > maximum) {
                throw new RequestTooLargeException(maximum);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
