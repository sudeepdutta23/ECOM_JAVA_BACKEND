package com.ecommerce.backend.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/checkout}. The cart itself is not sent: the server reads it. The
 * total is sent so the server can refuse when prices changed after the customer looked.
 */
@Schema(description = "Buy what is in your cart")
public record CheckoutRequest(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415", description = "One of your saved addresses")
        @NotNull
        UUID shippingAddressId,

        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415", description = "Optional; the shipping address if left out")
        UUID billingAddressId,

        @Schema(example = "39.98", description = "The total you were shown (GET /api/v1/cart)")
        @NotNull @DecimalMin("0.01") @Digits(integer = 9, fraction = 2)
        BigDecimal expectedTotal) {
}
