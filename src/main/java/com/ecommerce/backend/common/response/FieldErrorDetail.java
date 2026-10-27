package com.ecommerce.backend.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One validation failure on one input field.
 *
 * @param field   the JSON property or parameter name, e.g. {@code email}
 * @param message human-readable reason, safe to show to the end user
 */
@Schema(description = "A single field validation error")
public record FieldErrorDetail(
        @Schema(example = "email") String field,
        @Schema(example = "must be a well-formed email address") String message) {
}
