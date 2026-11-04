package com.ecommerce.backend.order;

import java.util.UUID;

/**
 * Something happened to an order. Published inside the transaction that changed it, so listeners
 * can either join that transaction (in-app notifications) or wait for its commit (emails).
 *
 * <p>Only IDs, amounts and the seller's public name: no addresses, no reasons, nothing a listener
 * could leak by logging the event.
 *
 * @param sellerName the seller's name at checkout, as the customer knows it
 */
public sealed interface OrderEvent {

    UUID orderId();

    UUID organizationId();

    /** Null once the customer's account was deleted. */
    UUID customerId();

    String sellerName();

    /** The checkout was paid: the customer has a confirmed order, the seller something to ship. */
    record Paid(UUID orderId, UUID organizationId, UUID customerId, String sellerName, long totalMinor, String currency)
            implements OrderEvent {
    }

    record Shipped(UUID orderId, UUID organizationId, UUID customerId, String sellerName, String trackingNumber)
            implements OrderEvent {
    }

    record Delivered(UUID orderId, UUID organizationId, UUID customerId, String sellerName) implements OrderEvent {
    }

    /** Who cancelled an order; decides who is told and how. */
    enum Canceller {
        CUSTOMER,
        SELLER,
        /** The platform: not paid in time (Phase 12). */
        SYSTEM
    }

    record Cancelled(UUID orderId, UUID organizationId, UUID customerId, String sellerName, Canceller by,
                     boolean refunded) implements OrderEvent {
    }
}
