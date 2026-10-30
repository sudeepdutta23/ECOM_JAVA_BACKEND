package com.ecommerce.backend.common.exception;

import com.ecommerce.backend.common.response.FieldErrorDetail;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every exception into an RFC 9457 Problem Details response
 * ({@code Content-Type: application/problem+json}).
 *
 * <p>Response shape:
 * <pre>{@code
 * {
 *   "type": "urn:problem-type:validation-failed",
 *   "title": "Validation failed",
 *   "status": 400,
 *   "detail": "One or more fields are invalid",
 *   "instance": "/api/v1/auth/register",
 *   "code": "VALIDATION_FAILED",
 *   "timestamp": "2026-10-26T10:15:30Z",
 *   "requestId": "9f1c2a4e-...",
 *   "errors": [ { "field": "email", "message": "must be a well-formed email address" } ]
 * }
 * }</pre>
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives correct statuses for all standard
 * Spring MVC exceptions (404 no handler, 405, 415, missing parameters, ...). We only enrich them.
 *
 * <p>Note: exceptions thrown inside Spring Security filters never reach this class. Phase 1 adds
 * an {@code AuthenticationEntryPoint} and {@code AccessDeniedHandler} that write the same shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------------------------------------------------------------------------------------
    // Our own exceptions
    // ---------------------------------------------------------------------------------------

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        log.info("Business rule rejected request: code={}, message={}", code, ex.getMessage());
        List<FieldErrorDetail> errors = ex instanceof RequestValidationException rve ? rve.getErrors() : null;
        return build(code, ex.getMessage(), request.getRequestURI(), errors);
    }

    // ---------------------------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------------------------

    /** {@code @Valid @RequestBody} failures. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldErrorDetail::field))
                .toList();
        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid", pathOf(request), errors);
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    /** {@code @Validated} service/controller method parameter failures. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<FieldErrorDetail> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldErrorDetail(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .sorted(Comparator.comparing(FieldErrorDetail::field))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid",
                request.getRequestURI(), errors);
    }

    /** Unparseable JSON, wrong types, or unknown fields. Never echo the parser message. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        String detail = "Request body is missing or malformed";
        if (ex.getCause() instanceof UnrecognizedPropertyException unknown) {
            detail = "Unknown field '" + unknown.getPropertyName() + "'";
        }
        ProblemDetail body = problem(ErrorCode.MALFORMED_REQUEST, detail, pathOf(request), null);
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    // ---------------------------------------------------------------------------------------
    // Database
    // ---------------------------------------------------------------------------------------

    /**
     * Last line of defence for unique/foreign-key violations (for example two concurrent
     * registrations with the same email). Services should check first and throw
     * {@link DuplicateResourceException}; this catches the race the check cannot prevent.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        // Log WHICH rule broke, never the database message: PostgreSQL includes the offending
        // values ("Key (email)=(alice@example.com) already exists"), which is personal data.
        log.warn("Data integrity violation: {}", constraintOf(ex));
        return build(ErrorCode.DATA_INTEGRITY_VIOLATION, ErrorCode.DATA_INTEGRITY_VIOLATION.title(),
                request.getRequestURI(), null);
    }

    // ---------------------------------------------------------------------------------------
    // Fallback
    // ---------------------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Quote the requestId when contacting support.",
                request.getRequestURI(), null);
    }

    /**
     * Final step for every response built by the parent class, called once the Problem Detail
     * body exists. We add our fields so built-in errors (404, 405, 415...) look like ours.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body,
                                                          HttpHeaders headers,
                                                          HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            ProblemDetails.enrich(pd, ErrorCode.fromStatus(statusCode.value()), pathOf(request));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static ResponseEntity<ProblemDetail> build(ErrorCode code, String detail, String path,
                                                       @Nullable List<FieldErrorDetail> errors) {
        return ResponseEntity.status(code.status()).body(ProblemDetails.of(code, detail, path, errors));
    }

    private static ProblemDetail problem(ErrorCode code, String detail, String path,
                                         @Nullable List<FieldErrorDetail> errors) {
        return ProblemDetails.of(code, detail, path, errors);
    }

    private static String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "/";
    }

    /** Constraint name from Hibernate if available, otherwise the SQLSTATE code. Never values. */
    static String constraintOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve && cve.getConstraintName() != null) {
                return "constraint=" + cve.getConstraintName();
            }
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return "sqlState=" + sql.getSQLState();
            }
        }
        return "cause=" + ex.getClass().getSimpleName();
    }

    private static String lastNode(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot >= 0 ? propertyPath.substring(dot + 1) : propertyPath;
    }
}
