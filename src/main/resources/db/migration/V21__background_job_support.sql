-- =====================================================================
-- V21: What the background jobs need (Phase 12)
--
-- 1. A notification type for orders the platform cancelled because they were not paid in time.
-- 2. Indexes so each job finds its rows without scanning a table that only grows.
--
-- Design: docs/modules/JOBS.md.
-- =====================================================================

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN ('ORDER_CONFIRMED', 'ORDER_SHIPPED',
    'ORDER_DELIVERED', 'ORDER_CANCELLED_BY_SELLER', 'ORDER_EXPIRED', 'NEW_ORDER', 'ORDER_CANCELLED_BY_CUSTOMER'));

-- Unpaid orders, oldest first: only orders waiting for payment are in it, so it stays small.
CREATE INDEX idx_orders_pending_payment ON orders (created_at) WHERE status = 'PENDING_PAYMENT';

-- Notification retention: the oldest notifications first.
CREATE INDEX idx_notifications_created_at ON notifications (created_at);

-- Spent email links: consumed ones are deleted after a day (expired ones by idx_account_tokens_expires_at).
CREATE INDEX idx_account_tokens_consumed_at ON account_tokens (consumed_at) WHERE consumed_at IS NOT NULL;
