-- =====================================================================
-- V16: Notifications (Phase 7)
--
-- 1. notifications: the in-app inbox, written in the same transaction as the order change
-- 2. notification_preferences: which emails a person wants; security emails are not optional
--
-- Design: docs/modules/NOTIFICATIONS.md.
-- =====================================================================

CREATE TABLE notifications (
    id         UUID          NOT NULL,
    user_id    UUID          NOT NULL,
    type       VARCHAR(40)   NOT NULL,
    title      VARCHAR(200)  NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    -- Where the frontend should go: a path inside the app, never an external URL.
    link       VARCHAR(300)  NOT NULL,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_notifications_type CHECK (type IN ('ORDER_CONFIRMED', 'ORDER_SHIPPED', 'ORDER_DELIVERED',
        'ORDER_CANCELLED_BY_SELLER', 'NEW_ORDER', 'ORDER_CANCELLED_BY_CUSTOMER')),
    CONSTRAINT ck_notifications_link_internal CHECK (link ~ '^/[A-Za-z0-9/_-]*$')
);

COMMENT ON TABLE notifications IS 'In-app notifications. Owned by the notification module.';

-- The inbox, newest first.
CREATE INDEX idx_notifications_user_id ON notifications (user_id, id);
-- The unread badge: only unread rows are indexed, so the count stays cheap however old the inbox.
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

CREATE TABLE notification_preferences (
    user_id             UUID        NOT NULL,
    -- Customer emails about their own orders: confirmed, shipped, cancelled by the seller.
    order_updates_email BOOLEAN     NOT NULL DEFAULT true,
    -- Seller emails: a new order to ship. One per member, so large teams may switch it off.
    seller_orders_email BOOLEAN     NOT NULL DEFAULT true,
    version             BIGINT      NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_notification_preferences PRIMARY KEY (user_id),
    CONSTRAINT fk_notification_preferences_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
