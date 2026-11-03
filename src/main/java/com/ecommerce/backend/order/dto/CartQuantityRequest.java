package com.ecommerce.backend.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/v1/cart/items/{productId}}: the new quantity, not an increment, so retrying is harmless. */
@Schema(description = "How many of this product you want")
public record CartQuantityRequest(
        @Schema(example = "2")
        @NotNull @Min(1) @Max(99)
        Integer quantity) {
}
