-- =====================================================================
-- V14: Cart (Phase 6, step 6.2)
--
-- One cart per account: simply the account's cart items. A cart may hold products of many sellers;
-- checkout (step 6.3) turns it into one order per seller.
--
-- The cart stores quantities only, never prices: the price shown is always the current one, and
-- checkout confirms it against the total the customer saw.
--
-- Design: docs/modules/ORDERS.md.
-- =====================================================================

CREATE TABLE cart_items (
    user_id    UUID        NOT NULL,
    product_id UUID        NOT NULL,
    quantity   INTEGER     NOT NULL,
    added_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One line per product: adding it again changes the quantity.
    CONSTRAINT pk_cart_items PRIMARY KEY (user_id, product_id),
    -- CASCADE: deleting an account deletes its cart. Erasure deletes it explicitly (OrderEraser).
    CONSTRAINT fk_cart_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- RESTRICT is never hit: products are archived, not deleted.
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_cart_items_quantity CHECK (quantity BETWEEN 1 AND 99)
);

COMMENT ON TABLE cart_items IS 'Shopping carts: one per account. Owned by the order module.';

-- The FK to products: "which carts hold this product" without a full scan.
CREATE INDEX idx_cart_items_product_id ON cart_items (product_id);
