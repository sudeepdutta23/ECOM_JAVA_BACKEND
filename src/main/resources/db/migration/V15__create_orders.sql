-- =====================================================================
-- V15: Checkout and orders (Phase 6, step 6.3)
--
-- 1. checkouts: one per checkout attempt that succeeded, keyed by the client's Idempotency-Key
-- 2. orders: one per seller in a checkout (tenant data: organization_id), with snapshots
-- 3. order_items: the lines, with the product's name and price at the time of purchase
--
-- Orders copy everything they need (names, prices, addresses). Editing a product or an address
-- later never changes what was bought, for how much, or where it went.
--
-- Design: docs/modules/ORDERS.md.
-- =====================================================================

CREATE TABLE checkouts (
    id              UUID        NOT NULL,
    -- SET NULL: orders outlive an account deletion (tax law). Erasure keeps the anonymous row anyway.
    customer_id     UUID,
    -- Chosen by the client per attempt; the same key returns the same result instead of buying twice.
    idempotency_key VARCHAR(64) NOT NULL,
    total_minor     BIGINT      NOT NULL,
    currency        VARCHAR(3)  NOT NULL,
    -- The payment provider's reference for the charge (step 6.4). Never card data: the client
    -- sends a provider token, the server never sees a card number.
    payment_reference VARCHAR(100),
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_checkouts PRIMARY KEY (id),
    CONSTRAINT ck_checkouts_paid_has_reference CHECK ((paid_at IS NULL) = (payment_reference IS NULL)),
    CONSTRAINT fk_checkouts_customer FOREIGN KEY (customer_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uk_checkouts_customer_key UNIQUE (customer_id, idempotency_key),
    CONSTRAINT ck_checkouts_key_format CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
    CONSTRAINT ck_checkouts_total CHECK (total_minor > 0),
    CONSTRAINT ck_checkouts_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE orders (
    id                UUID         NOT NULL,
    checkout_id       UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    -- The seller's name at checkout, for receipts and history: a later rename or closure changes nothing.
    seller_name       VARCHAR(100) NOT NULL,
    customer_id       UUID,
    status            VARCHAR(20)  NOT NULL,
    currency          VARCHAR(3)   NOT NULL,
    total_minor       BIGINT       NOT NULL,
    -- Snapshots of the customer's addresses at checkout, as JSON documents: immutable, read whole.
    shipping_address  JSONB        NOT NULL,
    billing_address   JSONB        NOT NULL,
    tracking_number   VARCHAR(100),
    cancel_reason     VARCHAR(500),
    cancelled_by      UUID,
    paid_at           TIMESTAMPTZ,
    shipped_at        TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ,
    cancelled_at      TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT fk_orders_checkout FOREIGN KEY (checkout_id) REFERENCES checkouts (id),
    CONSTRAINT fk_orders_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_orders_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_orders_status
        CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_orders_total CHECK (total_minor > 0),
    CONSTRAINT ck_orders_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- Each status carries its own timestamp, so the history can be read off the row.
    CONSTRAINT ck_orders_paid_has_time
        CHECK (status NOT IN ('PAID', 'SHIPPED', 'DELIVERED') OR paid_at IS NOT NULL),
    CONSTRAINT ck_orders_shipped_has_time CHECK (status NOT IN ('SHIPPED', 'DELIVERED') OR shipped_at IS NOT NULL),
    CONSTRAINT ck_orders_delivered_has_time CHECK (status <> 'DELIVERED' OR delivered_at IS NOT NULL),
    CONSTRAINT ck_orders_cancelled_has_time CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
);

COMMENT ON TABLE  orders                  IS 'One order per seller per checkout (tenant data). Owned by the order module. Kept for tax law.';
COMMENT ON COLUMN orders.shipping_address IS 'Copy of the address at checkout. Personal data: never logged.';

CREATE TABLE order_items (
    order_id         UUID         NOT NULL,
    line_no          INTEGER      NOT NULL,
    product_id       UUID         NOT NULL,
    sku              VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    unit_price_minor BIGINT       NOT NULL,
    quantity         INTEGER      NOT NULL,

    CONSTRAINT pk_order_items PRIMARY KEY (order_id, line_no),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_order_items_price CHECK (unit_price_minor > 0),
    CONSTRAINT ck_order_items_quantity CHECK (quantity BETWEEN 1 AND 99)
);

-- "My orders", newest first.
CREATE INDEX idx_orders_customer_id ON orders (customer_id, id);
-- The seller's order list, newest first, optionally by status.
CREATE INDEX idx_orders_organization_status ON orders (organization_id, status, id);
CREATE INDEX idx_orders_organization_id ON orders (organization_id, id);
CREATE INDEX idx_orders_checkout_id ON orders (checkout_id);
CREATE INDEX idx_orders_cancelled_by ON orders (cancelled_by);
CREATE INDEX idx_order_items_product_id ON order_items (product_id);
