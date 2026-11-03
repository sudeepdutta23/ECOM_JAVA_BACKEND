package com.ecommerce.backend.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST .../products/{productId}/stock}: a change, not a new total. Two people
 * booking in deliveries at once both count, which "set stock to N" would not guarantee. Zero is
 * rejected by the service.
 */
@Schema(description = "Add (positive) or remove (negative) units of stock")
public record StockAdjustmentRequest(
        @Schema(example = "25")
        @NotNull @Min(-10_000_000) @Max(10_000_000)
        Integer delta) {
}
