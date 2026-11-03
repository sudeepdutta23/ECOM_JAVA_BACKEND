package com.ecommerce.backend.order.dto;

import com.ecommerce.backend.common.util.MinorUnits;
import com.ecommerce.backend.order.OrderStatus;
import com.ecommerce.backend.order.entity.SalesOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An order in a list: no lines and no addresses, so a page is one query. */
@Schema(description = "An order in a list")
public record OrderSummary(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        UUID checkoutId,
        @Schema(example = "Acme Store") String sellerName,
        @Schema(example = "PAID") OrderStatus status,
        @Schema(example = "39.98") BigDecimal total,
        @Schema(example = "EUR") String currency,
        Instant createdAt) {

    public static OrderSummary from(SalesOrder order) {
        return new OrderSummary(order.getId(), order.getCheckoutId(), order.getSellerName(), order.getStatus(),
                MinorUnits.toAmount(order.getTotalMinor()), order.getCurrency(), order.getCreatedAt());
    }
}
