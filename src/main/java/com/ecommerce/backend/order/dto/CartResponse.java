package com.ecommerce.backend.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The cart at current prices. Lines that cannot be bought right now stay in the cart, marked with
 * a problem, and do not count towards the total: the customer decides what to do with them.
 */
@Schema(description = "Your cart at current prices")
public record CartResponse(
        List<Line> lines,
        @Schema(example = "39.98", description = "Sum of the lines that can be bought now") BigDecimal total,
        @Schema(example = "EUR") String currency,
        @Schema(example = "2", description = "Units in the lines that can be bought now") int itemCount,
        @Schema(description = "True when the cart is not empty and every line can be bought") boolean readyForCheckout) {

    /** Why a line cannot be bought right now. */
    public enum Problem {
        /** Archived, or its seller is not selling (pending, suspended, closed). */
        NOT_ON_SALE,
        /** On sale, but fewer units are left than the line asks for. */
        NOT_ENOUGH_STOCK
    }

    public record Line(
            @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID productId,
            @Schema(example = "Enamel mug, blue") String name,
            @Schema(description = "Absent while the seller is not selling") Seller seller,
            @Schema(example = "19.99") BigDecimal unitPrice,
            @Schema(example = "2") int quantity,
            @Schema(example = "39.98") BigDecimal lineTotal,
            @Schema(description = "Absent when the line can be bought") Problem problem) {
    }

    public record Seller(@Schema(example = "acme-store") String slug, @Schema(example = "Acme Store") String name) {
    }
}
