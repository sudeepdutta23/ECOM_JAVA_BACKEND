package com.ecommerce.backend.common.exception;

/** Thrown when creating a resource would violate a uniqueness rule. */
public class DuplicateResourceException extends BusinessException {

    public DuplicateResourceException(String message) {
        super(ErrorCode.DUPLICATE_RESOURCE, message);
    }
}
