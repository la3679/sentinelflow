/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes an RFC 9457 problem straight to the response, for refusals made before any controller runs.
 *
 * <p>Four things now refuse a request inside a filter: the security chain's entry point and access
 * denied handler, the request size limit, the rate limiter, and the ingestion key check. Every one of
 * them has to produce the {@code application/problem+json} body the OpenAPI contract promises, and
 * none of them can reach {@code ApiExceptionHandler} — a filter throws before the dispatcher picks a
 * handler, so no {@code @ExceptionHandler} sees it.
 *
 * <p>This exists so that shape is written once. Four copies of the same eight lines is four places a
 * field can be forgotten, and the reason a client can have one error parser is that every error is
 * genuinely the same.
 */
@Component
public class ProblemWriter {

    /** The same prefix {@code ApiExceptionHandler} uses. A type URI that varied by writer would be worthless. */
    public static final String TYPE_PREFIX = "https://sentinelflow.example/problems/";

    private final ObjectMapper objectMapper;

    public ProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Writes the problem and commits the response.
     *
     * @param type the last segment of the problem type URI, which is the stable identifier a client
     *     matches on. Never a sentence, never localised.
     * @param detail what happened, in terms the caller can act on, and never anything the caller did
     *     not already know.
     */
    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String type,
            String title,
            String detail)
            throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + type));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
