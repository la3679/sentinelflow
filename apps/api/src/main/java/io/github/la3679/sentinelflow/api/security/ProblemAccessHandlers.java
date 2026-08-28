/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * The two refusals the filter chain makes before any controller runs, in the shape every other
 * error from this API has.
 *
 * <p>Spring Security answers an unauthenticated request with an empty 401 and a denied one with an
 * empty 403. Both are correct and neither is what this API promises: the OpenAPI contract says
 * errors are RFC 9457 {@code application/problem+json}, and a client with one parser for errors
 * should not need a second one for the two that happen to be produced by a filter.
 *
 * <p><strong>Written here rather than delegated to {@code ApiExceptionHandler}.</strong> These are
 * thrown before the dispatcher picks a handler, so no {@code @ExceptionHandler} sees them. Routing
 * them into one through a {@code HandlerExceptionResolver} is possible and is more machinery than
 * writing two objects to a stream.
 *
 * <p><strong>Neither says anything the caller does not already know.</strong> A 401 says a valid
 * token is needed and not why this one was not valid — expired, malformed and forged are all the
 * same answer, because telling a caller which would help exactly one kind of caller. A 403 says the
 * role is insufficient without naming which role would do.
 */
@Component
public class ProblemAccessHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String TYPE_PREFIX = "https://sentinelflow.example/problems/";

    private final ObjectMapper objectMapper;

    public ProblemAccessHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        // The WWW-Authenticate header is what makes this a well-behaved 401
        // rather than a 403 with the wrong number on it. Spring Security's own
        // entry point sets it; replacing the body must not lose it.
        response.setHeader("WWW-Authenticate", "Bearer");
        write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "unauthenticated",
                "Authentication required",
                "This endpoint requires a valid bearer token.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "insufficient-role",
                "Insufficient role",
                "The authenticated role does not permit this operation.");
    }

    private void write(
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
