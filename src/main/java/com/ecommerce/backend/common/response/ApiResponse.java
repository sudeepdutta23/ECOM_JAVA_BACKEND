package com.ecommerce.backend.common.response;

import com.ecommerce.backend.common.web.RequestIdFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Envelope for every SUCCESSFUL response.
 *
 * <p>Errors do not use this type. They use RFC 9457 Problem Details
 * ({@link org.springframework.http.ProblemDetail}), built by
 * {@link com.ecommerce.backend.common.exception.GlobalExceptionHandler}.
 *
 * @param <T> payload type
 */
@Schema(description = "Standard envelope for successful responses")
public record ApiResponse<T>(
        @Schema(example = "true") boolean success,
        @Schema(example = "Operation completed successfully") String message,
        T data,
        @Schema(example = "2026-10-26T10:15:30.123Z") Instant timestamp,
        @Schema(example = "9f1c2a4e-1b7d-4c55-9a0e-3f2b8c6d7e81") String requestId) {

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data, Instant.now(), RequestIdFilter.currentRequestId());
    }

    public static ApiResponse<Void> success(String message) {
        return success(message, null);
    }
}
