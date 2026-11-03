package com.ecommerce.backend.order.dto;

import com.ecommerce.backend.common.util.MinorUnits;
import com.ecommerce.backend.order.OrderStatus;
import com.ecommerce.backend.order.entity.AddressSnapshot;
import com.ecommerce.backend.order.entity.OrderLine;
import com.ecommerce.backend.order.entity.SalesOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One order, with its lines and addresses as they were at checkout. Seen by its customer and its seller. */
@Schema(description = "An order")
public record OrderResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        UUID checkoutId,
        UUID organizationId,
        @Schema(example = "Acme Store", description = "The seller's name at checkout") String sellerName,
        @Schema(example = "PENDING_PAYMENT") OrderStatus status,
        @Schema(example = "39.98") BigDecimal total,
        @Schema(example = "EUR") String currency,
        List<Line> lines,
        AddressSnapshot shippingAddress,
        AddressSnapshot billingAddress,
        String trackingNumber,
        String cancelReason,
        Instant paidAt,
        Instant shippedAt,
        Instant deliveredAt,
        Instant cancelledAt,
        Instant createdAt,
        @Schema(example = "0") long version) {

    public record Line(UUID productId, @Schema(example = "MUG-001") String sku, @Schema(example = "Enamel mug") String name,
                       @Schema(example = "19.99") BigDecimal unitPrice, @Schema(example = "2") int quantity,
                       @Schema(example = "39.98") BigDecimal lineTotal) {

        static Line from(OrderLine line) {
            return new Line(line.getProductId(), line.getSku(), line.getName(), MinorUnits.toAmount(line.getUnitPriceMinor()),
                    line.getQuantity(), MinorUnits.toAmount(line.totalMinor()));
        }
    }

    public static OrderResponse from(SalesOrder order) {
        return new OrderResponse(order.getId(), order.getCheckoutId(), order.getOrganizationId(), order.getSellerName(),
                order.getStatus(), MinorUnits.toAmount(order.getTotalMinor()), order.getCurrency(),
                order.getLines().stream().map(Line::from).toList(), order.getShippingAddress(), order.getBillingAddress(),
                order.getTrackingNumber(), order.getCancelReason(), order.getPaidAt(), order.getShippedAt(),
                order.getDeliveredAt(), order.getCancelledAt(), order.getCreatedAt(), order.getVersion());
    }

    /** Addresses and reasons are personal data, and toString ends up in logs. */
    @Override
    public String toString() {
        return "OrderResponse[id=" + id + ", status=" + status + "]";
    }
}
