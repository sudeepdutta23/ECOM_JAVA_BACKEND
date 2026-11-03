package com.ecommerce.backend.order.dto;

import com.ecommerce.backend.common.util.MinorUnits;
import com.ecommerce.backend.order.entity.Checkout;
import com.ecommerce.backend.order.entity.SalesOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A checkout: one order per seller, paid for together (step 6.4). */
@Schema(description = "A checkout and its orders, one per seller")
public record CheckoutResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "39.98") BigDecimal total,
        @Schema(example = "EUR") String currency,
        Instant createdAt,
        List<OrderResponse> orders) {

    public static CheckoutResponse from(Checkout checkout, List<SalesOrder> orders) {
        return new CheckoutResponse(checkout.getId(), MinorUnits.toAmount(checkout.getTotalMinor()), checkout.getCurrency(),
                checkout.getCreatedAt(), orders.stream().map(OrderResponse::from).toList());
    }
}
