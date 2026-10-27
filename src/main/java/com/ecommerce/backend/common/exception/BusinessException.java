package com.ecommerce.backend.common.exception;

/**
 * Base class for every expected, domain-level failure.
 *
 * <p>The message MUST be safe to show to API consumers: no SQL, no stack traces, no internal IDs
 * the caller did not supply. Unexpected failures should NOT use this class; let them propagate
 * so they are logged and returned as a generic 500.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.title());
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
