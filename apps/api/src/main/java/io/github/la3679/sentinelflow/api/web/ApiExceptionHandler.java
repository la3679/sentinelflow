/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.github.la3679.sentinelflow.api.alert.AlertTransitions;
import io.github.la3679.sentinelflow.api.report.AlertReportService.ExportTooLargeException;
import io.github.la3679.sentinelflow.api.security.OperatorLoginService.InvalidCredentialsException;
import io.github.la3679.sentinelflow.api.service.exception.AlertClosedException;
import io.github.la3679.sentinelflow.api.service.exception.AlertNotFoundException;
import io.github.la3679.sentinelflow.api.service.exception.AlertVersionConflictException;
import io.github.la3679.sentinelflow.api.service.exception.AssessmentNotFoundException;
import io.github.la3679.sentinelflow.api.service.exception.IdempotencyConflictException;
import io.github.la3679.sentinelflow.api.service.exception.IllegalAlertTransitionException;
import io.github.la3679.sentinelflow.api.service.exception.InsufficientRoleException;
import io.github.la3679.sentinelflow.api.service.exception.InvalidAssigneeException;
import io.github.la3679.sentinelflow.api.service.exception.InvalidWindowException;
import io.github.la3679.sentinelflow.api.service.exception.TransactionNotFoundException;
import io.github.la3679.sentinelflow.api.service.exception.UnknownReferenceException;
import io.github.la3679.sentinelflow.api.web.limit.RequestSizeLimitFilter;
import io.github.la3679.sentinelflow.api.web.limit.RequestTooLargeException;

/**
 * The single place an exception becomes a response body.
 *
 * <p>RFC 9457 {@code application/problem+json} throughout, which Spring's {@link ProblemDetail}
 * produces natively. Every response carries the request's correlation identifier, so a caller
 * reporting a failure hands over something that finds the exact request in the logs.
 *
 * <p><strong>Nothing internal escapes.</strong> No stack trace, no SQL fragment, no class name, no
 * message from an exception this code did not write. The unhandled case returns a fixed sentence
 * and logs the real cause server-side — an error response is read by whoever sent the request,
 * which in a real deployment includes people who should learn nothing from it. That is also why
 * this class exists at all rather than the exception reaching Spring's default handler.
 *
 * <p>Handled once, here. Per {@code docs/development/ENGINEERING_STANDARDS.md}, no layer below catches and logs and
 * rethrows on the way up: one log line, written where the decision about what to do is made.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Problem type URIs. Stable, dereferenceable-looking, and versioned with the API - a client may
     * branch on these, so they are part of the contract rather than decoration.
     */
    private static final String TYPE_PREFIX = "https://sentinelflow.example/problems/";

    /** Long enough for any route this API serves, short enough that a hostile URI cannot flood a line. */
    private static final int MAX_LOGGED_REQUEST_CHARS = 200;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "validation-failed",
                "Validation failed",
                "One or more fields are invalid. See errors.",
                request);

        // Sorted by field name so a client diffing two responses sees a stable
        // order, and so a test can assert one.
        List<FieldProblem> errors = new ArrayList<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            // getDefaultMessage is the Bean Validation message from this
            // application's own annotations, never anything the caller sent.
            errors.add(new FieldProblem(fieldError.getField(), fieldError.getDefaultMessage()));
        }
        errors.sort(Comparator.comparing(FieldProblem::field));
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail onUnreadableBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        // A body cut off by the size limit arrives here, because the parser is
        // what notices its input ended early. It is a 413 and not a 400: the
        // request was well formed and too big, and telling a caller their JSON
        // is malformed when it is merely long sends them to debug the wrong
        // thing (ADR-0017 section 3).
        RequestTooLargeException tooLarge = causedByRequestTooLarge(exception);
        if (tooLarge != null) {
            return onRequestTooLarge(tooLarge, request);
        }

        // The parser's own message names offsets, field paths and sometimes the
        // offending value. None of that goes to the client; the log keeps it.
        log.debug("Rejected an unreadable request body", exception);
        return problem(
                HttpStatus.BAD_REQUEST,
                "malformed-request",
                "Malformed request",
                "The request body is not valid JSON, or a field has the wrong type.",
                request);
    }

    /**
     * A body that ran past the configured maximum (ADR-0017 §3).
     *
     * <p>Registered for the exception itself as well as unwrapped above, because the stream can throw
     * it before a parser is involved at all — a caller that opens a body and abandons it, for one.
     * Both paths answer with the same status and the same problem type, so which half caught it is
     * not something a caller can tell.
     */
    @ExceptionHandler(RequestTooLargeException.class)
    ProblemDetail onRequestTooLarge(RequestTooLargeException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                RequestSizeLimitFilter.PROBLEM_TYPE,
                "Request body too large",
                "The request body exceeds the " + exception.maximumBytes() + " byte maximum this API accepts.",
                request);
    }

    /**
     * Strips anything a caller could use to forge a log line.
     *
     * <p>The method and the request URI are both caller-supplied. Tomcat rejects control characters
     * in a request line and returns the URI still percent-encoded, so a newline does not arrive
     * intact today — but that is a property of the container in front of this code rather than of
     * this code, and it is not one worth resting on. {@code CorrelationIdFilter} already refuses to
     * echo arbitrary client text for exactly this reason; this is the same rule applied at the one
     * other place a caller's bytes reach a log line.
     *
     * <p>Carriage returns and newlines become spaces, and the value is capped, so a long URI cannot
     * push the rest of the entry out of a viewer.
     */
    private static String sanitiseForLog(String value) {
        if (value == null) {
            return "";
        }
        String flattened = value.replace('\r', ' ').replace('\n', ' ');
        return flattened.length() <= MAX_LOGGED_REQUEST_CHARS
                ? flattened
                : flattened.substring(0, MAX_LOGGED_REQUEST_CHARS) + "…";
    }

    /** Walks the cause chain, because the parser wraps whatever its input threw. */
    private static RequestTooLargeException causedByRequestTooLarge(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestTooLargeException tooLarge) {
                return tooLarge;
            }
            if (cause.getCause() == cause) {
                // A self-referential cause chain is malformed rather than
                // impossible, and walking one forever is a hang, not an error.
                break;
            }
        }
        return null;
    }

    @ExceptionHandler(UnknownReferenceException.class)
    ProblemDetail onUnknownReference(UnknownReferenceException exception, HttpServletRequest request) {
        // 422, not 404: the request is well-formed and the route exists; the
        // entity it names does not. A 404 here would say the endpoint is
        // missing, which is a different problem for a client to chase.
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unknown-reference",
                "Unknown reference",
                "No " + exception.field() + " matches the reference supplied.",
                request);
        problem.setProperty("errors", List.of(new FieldProblem(exception.field(), "No such " + exception.field())));
        return problem;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail onIdempotencyConflict(IdempotencyConflictException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "idempotency-conflict",
                "Idempotency key reused with a different payload",
                "This idempotency key was already used on this account for a different transaction. "
                        + "Returning the original result would hide a key-generation bug, so the request is refused.",
                request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail onInvalidCredentials(InvalidCredentialsException exception, HttpServletRequest request) {
        // One sentence, and the same one whether the username was unknown, the
        // account disabled, or the password wrong. Distinguishing them would
        // turn an endpoint that is necessarily open into an oracle for which
        // usernames exist. The service logs which it was.
        return problem(
                HttpStatus.UNAUTHORIZED,
                "invalid-credentials",
                "Invalid credentials",
                "The username and password were not accepted.",
                request);
    }

    @ExceptionHandler(AlertNotFoundException.class)
    ProblemDetail onAlertNotFound(AlertNotFoundException exception, HttpServletRequest request) {
        // 404, unlike UnknownReferenceException's 422: an alert identifier is
        // part of the path rather than of a payload, so "there is nothing here"
        // is the accurate answer and the one a client's router expects.
        return problem(
                HttpStatus.NOT_FOUND, "alert-not-found", "Alert not found", "No alert has that identifier.", request);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    ProblemDetail onTransactionNotFound(TransactionNotFoundException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.NOT_FOUND,
                "transaction-not-found",
                "Transaction not found",
                "No transaction has that identifier.",
                request);
    }

    @ExceptionHandler(AssessmentNotFoundException.class)
    ProblemDetail onAssessmentNotFound(AssessmentNotFoundException exception, HttpServletRequest request) {
        // 404, and the detail says which of the two 404s this is. Scoring
        // happens after ingestion answers, so a client polling for an
        // assessment needs to tell "you asked for a transaction that does not
        // exist" from "it exists and has not been scored yet" - the first is
        // their bug and the second is this system still working.
        return problem(
                HttpStatus.NOT_FOUND,
                "assessment-not-found",
                "Assessment not available",
                "That transaction has not been assessed. Scoring happens after ingestion, so this may"
                        + " resolve on its own; a transaction whose processingStatus is FAILED will never"
                        + " have one.",
                request);
    }

    @ExceptionHandler(IllegalAlertTransitionException.class)
    ProblemDetail onIllegalTransition(IllegalAlertTransitionException exception, HttpServletRequest request) {
        // 409 rather than 400. The request is well formed and the target is a
        // real status; what refuses it is the state the alert is in, and that
        // state can change between one request and the next. Telling the caller
        // to fix their request would send them looking for a fault that is not
        // in it.
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "illegal-transition",
                "Illegal transition",
                "An alert in " + exception.from() + " cannot move to " + exception.to() + ".",
                request);
        problem.setProperty("currentStatus", exception.from().name());
        // What the caller may do instead. A property of the state machine
        // rather than of this alert, so naming it discloses nothing.
        //
        // Produced by the same helper the legalTargets field on an alert uses,
        // so a client comparing what it was offered against what a refusal
        // names cannot be shown two orderings of one answer.
        problem.setProperty("legalTargets", AlertTransitions.namesOf(exception.legalTargets()));
        return problem;
    }

    @ExceptionHandler(AlertVersionConflictException.class)
    ProblemDetail onVersionConflict(AlertVersionConflictException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "version-conflict",
                "The alert has changed",
                "Somebody else changed this alert since it was read. Re-read it and decide again.",
                request);
        problem.setProperty("expectedVersion", exception.expectedVersion());
        if (exception.actualVersion() != null) {
            problem.setProperty("currentVersion", exception.actualVersion());
        }
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail onAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        // @PreAuthorize throws inside the handler, which means the dispatcher
        // sees it before Spring Security's ExceptionTranslationFilter can - so
        // without this it falls through to the catch-all below and a role
        // refusal is served as a 500. Found by an auditor's token producing an
        // internal error instead of a 403.
        //
        // The same body ProblemAccessHandlers writes for a denial the filter
        // chain catches, because a client should not be able to tell which of
        // the two layers refused it.
        return problem(
                HttpStatus.FORBIDDEN,
                "insufficient-role",
                "Insufficient role",
                "The authenticated role does not permit this operation.",
                request);
    }

    @ExceptionHandler(InsufficientRoleException.class)
    ProblemDetail onInsufficientRole(InsufficientRoleException exception, HttpServletRequest request) {
        // 403 and not 401: the caller proved who they are, and the answer is
        // still no. A client should re-authenticate on one and never on the
        // other.
        return problem(
                HttpStatus.FORBIDDEN,
                "insufficient-role",
                "Insufficient role",
                "This operation requires the " + exception.required() + " role.",
                request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail onParameterValidationFailure(HandlerMethodValidationException exception, HttpServletRequest request) {
        // Constraints on method parameters - a page size above the cap, a
        // negative page - rather than on a request body. Without this they reach
        // the catch-all below and a client asking for 500 rows gets a 500 status
        // to match, which is the wrong answer to a request that is simply too
        // large.
        //
        // The same 422 and the same shape as a body validation failure, because
        // from a caller's side they are the same thing: the request was
        // understood and a value in it is not allowed.
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "validation-failed",
                "Validation failed",
                "One or more parameters are invalid. See errors.",
                request);

        // getParameterValidationResults rather than the visitor interface: the
        // visitor has a method per parameter kind - query parameter, path
        // variable, request part - and would have to implement every one of them
        // to say the same thing about each. (It is getAllValidationResults in
        // Spring 6; this module is on 7.)
        List<FieldProblem> errors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(error ->
                            errors.add(new FieldProblem(name == null ? "parameter" : name, error.getDefaultMessage())));
        }
        errors.sort(Comparator.comparing(FieldProblem::field));
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(AlertClosedException.class)
    ProblemDetail onAlertClosed(AlertClosedException exception, HttpServletRequest request) {
        // 409 for the same reason an illegal transition is one: the request is
        // well formed and the state refuses it. An assignee on a closed alert
        // would sit in "what is on this analyst's desk" for ever, and a note
        // added after a disposition reads as though it informed one.
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "alert-closed",
                "The investigation is over",
                "This alert is " + exception.status() + ", and that operation only applies while it is open.",
                request);
        problem.setProperty("currentStatus", exception.status().name());
        return problem;
    }

    @ExceptionHandler(InvalidAssigneeException.class)
    ProblemDetail onInvalidAssignee(InvalidAssigneeException exception, HttpServletRequest request) {
        // 422, like every other well-formed request naming something that
        // cannot be used. The message says a user cannot be assigned work and
        // never which users exist.
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid-assignee",
                "Cannot be assigned",
                "That user cannot be given an alert to work.",
                request);
        problem.setProperty("errors", List.of(new FieldProblem("assigneeId", "Cannot be assigned an alert")));
        return problem;
    }

    @ExceptionHandler(InvalidWindowException.class)
    ProblemDetail onInvalidWindow(InvalidWindowException exception, HttpServletRequest request) {
        // The message is generated here and names no data, so echoing it is
        // safe and is the whole value: "to must be after from" is actionable
        // where "invalid request" is not.
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid-window",
                "Invalid reporting window",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(ExportTooLargeException.class)
    ProblemDetail onExportTooLarge(ExportTooLargeException exception, HttpServletRequest request) {
        // 413 rather than 422: the request is entirely valid and the *response*
        // is what cannot be produced. A client should narrow the window rather
        // than look for a mistake in what it sent.
        ProblemDetail problem = problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "export-too-large",
                "Too many rows to export",
                "That window holds more alerts than one export carries. Narrow it and try again.",
                request);
        // Both numbers, so a client can narrow the window arithmetically rather
        // than by halving it until it works.
        problem.setProperty("rows", exception.rows());
        problem.setProperty("limit", exception.limit());
        return problem;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail onNoResource(NoResourceFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", "No handler for this path.", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail onUnhandled(Exception exception, HttpServletRequest request) {
        UUID correlationId = CorrelationIdFilter.currentOrNew(request);
        // The only place the real exception is recorded. Logged with the
        // correlation identifier so the fixed sentence the caller receives can
        // still be traced back to this line.
        log.error(
                "Unhandled exception serving {} {}",
                sanitiseForLog(request.getMethod()),
                sanitiseForLog(request.getRequestURI()),
                exception);

        ProblemDetail problem = problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "The request could not be completed. Quote the correlation identifier when reporting this.",
                request);
        problem.setProperty("correlationId", correlationId.toString());
        return problem;
    }

    private static ProblemDetail problem(
            HttpStatus status, String type, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + type));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty(
                "correlationId", CorrelationIdFilter.currentOrNew(request).toString());
        return problem;
    }

    /** One field-level failure, matching the `errors` array in the OpenAPI `Problem` schema. */
    public record FieldProblem(String field, String message) {}
}
