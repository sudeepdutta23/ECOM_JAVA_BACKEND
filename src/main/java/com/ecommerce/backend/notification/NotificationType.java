package com.ecommerce.backend.notification;

/** What a notification is about. Values must match {@code ck_notifications_type} (V16). */
public enum NotificationType {
    /** Customer: the order was paid and goes to the seller. */
    ORDER_CONFIRMED,
    ORDER_SHIPPED,
    ORDER_DELIVERED,
    ORDER_CANCELLED_BY_SELLER,
    /** Customer: not paid in time, cancelled by the platform (Phase 12). */
    ORDER_EXPIRED,
    /** Seller member: a paid order to ship. */
    NEW_ORDER,
    ORDER_CANCELLED_BY_CUSTOMER
}
