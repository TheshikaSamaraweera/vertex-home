package com.democode.mlmsittu.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * RFC 9457 problem responses for the whole API (architecture §9, development plan P0-06).
 *
 * <p>Two hard rules, both verified at Gate 0 and Gate 1:
 *
 * <ul>
 *   <li>Every body carries a machine-readable {@code code}. The frontend matches on that, never
 *       on message text.
 *   <li>No Java class name, stack trace or SQL fragment ever reaches the client. Unexpected
 *       failures are logged in full server-side and returned as a bare 500.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Problem type URIs are namespaced so they stay stable as documentation anchors. */
    private static final String TYPE_PREFIX = "https://mlmsittu.lk/problems/";

    // ---------------------------------------------------------------- deliberate errors

    /** Names RFC 9457 reserves at the top level of a problem body. */
    private static final Set<String> RESERVED_MEMBERS =
            Set.of("type", "title", "status", "detail", "instance", "code");

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(ex.getStatus(), ex.getCode(), ex.getMessage(), request);

        ex.getProperties()
                .forEach(
                        (key, value) -> {
                            // A custom property named "status" would emit a second "status" key
                            // alongside the HTTP status. Clients then read whichever their JSON
                            // parser happens to keep, which is not a coin flip anyone should be
                            // making. Prefix rather than drop, so the information survives.
                            String safeKey =
                                    RESERVED_MEMBERS.contains(key) ? "detail_" + key : key;
                            if (!safeKey.equals(key)) {
                                log.warn(
                                        "Problem property '{}' collides with an RFC 9457 reserved"
                                            + " member; emitted as '{}'. Rename it at the throw"
                                            + " site.",
                                        key,
                                        safeKey);
                            }
                            pd.setProperty(safeKey, value);
                        });
        return pd;
    }

    // ---------------------------------------------------------------- validation

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(
                        error ->
                                fieldErrors.putIfAbsent(
                                        error.getField(),
                                        error.getDefaultMessage() == null
                                                ? "INVALID"
                                                : error.getDefaultMessage()));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(
                        error ->
                                fieldErrors.putIfAbsent(
                                        error.getObjectName(),
                                        error.getDefaultMessage() == null
                                                ? "INVALID"
                                                : error.getDefaultMessage()));

        ProblemDetail pd =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED",
                        "One or more fields are invalid.",
                        request);
        pd.setProperty("errors", fieldErrors);
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        // The cause frequently contains the Jackson class path — never forward it.
        log.debug("Malformed request body on {}", request.getRequestURI(), ex);
        return problem(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST_BODY",
                "The request body could not be parsed as JSON.",
                request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        ProblemDetail pd =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "MISSING_PARAMETER",
                        "A required request parameter is missing.",
                        request);
        pd.setProperty("parameter", ex.getParameterName());
        return pd;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        ProblemDetail pd =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_PARAMETER",
                        "A request parameter has the wrong format.",
                        request);
        pd.setProperty("parameter", ex.getName());
        return pd;
    }

    // ---------------------------------------------------------------- security

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(
            AuthenticationException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED",
                "Authentication is required.",
                request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "Your role does not permit this action.",
                request);
    }

    // ---------------------------------------------------------------- routing

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(
            NoResourceFoundException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "No such endpoint.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "That HTTP method is not supported on this endpoint.",
                request);
    }

    // ---------------------------------------------------------------- catch-all

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        // Full detail to the log, nothing to the client. This is the rule Gate 0 checks.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred. The incident has been logged.",
                request);
    }

    // ---------------------------------------------------------------- helper

    private ProblemDetail problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(URI.create(TYPE_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        pd.setTitle(status.getReasonPhrase());
        pd.setDetail(detail);
        pd.setProperty("code", code);
        pd.setInstance(URI.create(request.getRequestURI()));
        return pd;
    }
}
