package com.ecommerce.backend.common.exception;

/** Thrown when a requested resource does not exist or is not visible to the caller. */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resourceName) {
        super(ErrorCode.RESOURCE_NOT_FOUND, resourceName + " not found");
    }
}
