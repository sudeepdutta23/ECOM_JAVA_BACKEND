package com.ecommerce.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes returned in the {@code code} field of every error.
 *
 * <p>Clients should branch on {@code code}, never on the human-readable message, because
 * messages may be reworded or translated while codes are part of the API contract.
 * Modules add their own codes here (for example {@code AUTH_INVALID_CREDENTIALS} in Phase 1).
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "Resource already exists"),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "Request conflicts with existing data"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violation"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable"),

    // ----- Authentication (Phase 1) -----
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Access token expired"),
    AUTH_INVALID_ACCOUNT_TOKEN(HttpStatus.BAD_REQUEST, "Invalid or expired link"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    AUTH_EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email not verified"),
    AUTH_ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "Account disabled"),
    AUTH_INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    AUTH_INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "Current password is incorrect");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Maps a framework-level HTTP status to the closest generic code. */
    public static ErrorCode fromStatus(int statusCode) {
        return switch (statusCode) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> DATA_INTEGRITY_VIOLATION;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> BUSINESS_RULE_VIOLATION;
            case 429 -> TOO_MANY_REQUESTS;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> statusCode >= 500 ? INTERNAL_ERROR : MALFORMED_REQUEST;
        };
    }
}
