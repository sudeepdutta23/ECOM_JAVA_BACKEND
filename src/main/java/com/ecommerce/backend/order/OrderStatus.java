package com.ecommerce.backend.order;

/**
 * An order's life cycle. Values must match {@code ck_orders_status} (V15).
 *
 * <pre>
 * PENDING_PAYMENT --pay--> PAID --ship--> SHIPPED --deliver--> DELIVERED
 *        └------cancel-------┴---cancel-------> CANCELLED (stock returned)
 * </pre>
 * Shipped and delivered orders are not cancelled; they are returned (a later phase).
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    public boolean isCancellable() {
        return this == PENDING_PAYMENT || this == PAID;
    }
}
