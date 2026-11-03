-- =====================================================================
-- V13: Catalogue (Phase 6, step 6.1)
--
-- 1. categories: one platform-wide list, managed by platform staff (catalog:manage)
-- 2. products: owned by a seller (organization_id), with price and stock
-- 3. Platform permission catalog:manage, granted to ADMIN
--
-- Design: docs/modules/CATALOG.md. Tenant rule (ADR-012): every seller-owned row carries
-- organization_id NOT NULL, and every lookup takes it.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Categories
-- ---------------------------------------------------------------------
CREATE TABLE categories (
    id         UUID         NOT NULL,
    -- Public handle in storefront URLs (?category=books). Lower-case, like seller slugs.
    slug       VARCHAR(50)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uk_categories_slug UNIQUE (slug),
    CONSTRAINT ck_categories_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$'),
    CONSTRAINT ck_categories_name_trimmed CHECK (name = btrim(name) AND name <> '')
);

COMMENT ON TABLE categories IS 'Platform-wide product categories. Owned by the catalog module.';

-- ---------------------------------------------------------------------
-- 2. Products
-- ---------------------------------------------------------------------
CREATE TABLE products (
    id              UUID          NOT NULL,
    organization_id UUID          NOT NULL,
    category_id     UUID,
    -- The seller's own article number, unique per seller, stored upper-case.
    sku             VARCHAR(64)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    description     VARCHAR(5000),
    -- Money in minor units (cents): exact, no floating point. One platform currency for now.
    price_minor     BIGINT        NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    stock           INTEGER       NOT NULL DEFAULT 0,
    status          VARCHAR(16)   NOT NULL,
    -- Copy of "the seller is ACTIVE", kept by the catalog in the same transaction as every seller
    -- status change (OrganizationStatusChanged). The storefront filters on it without reading
    -- the org module's tables.
    seller_active   BOOLEAN       NOT NULL DEFAULT false,
    created_by      UUID,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_products PRIMARY KEY (id),
    -- RESTRICT: organisations are closed, never deleted; their products stay for order history.
    CONSTRAINT fk_products_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    -- RESTRICT: a category in use cannot be deleted (409 CATALOG_CATEGORY_IN_USE).
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_products_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uk_products_organization_sku UNIQUE (organization_id, sku),
    CONSTRAINT ck_products_sku_format CHECK (sku ~ '^[A-Z0-9][A-Z0-9._-]{0,63}$'),
    CONSTRAINT ck_products_name_trimmed CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT ck_products_description_not_empty CHECK (btrim(description) <> ''),
    -- From 0.01 to 1 000 000.00 in the currency.
    CONSTRAINT ck_products_price_range CHECK (price_minor BETWEEN 1 AND 100000000),
    CONSTRAINT ck_products_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    -- Never negative: checkout decrements with "WHERE stock >= quantity" (step 6.3), and this CHECK
    -- is the last line if code ever forgot to.
    CONSTRAINT ck_products_stock_range CHECK (stock BETWEEN 0 AND 10000000),
    CONSTRAINT ck_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

COMMENT ON TABLE  products               IS 'Seller products (tenant data: organization_id). Owned by the catalog module.';
COMMENT ON COLUMN products.seller_active IS 'Copy of organizations.status = ACTIVE, maintained by the catalog on OrganizationStatusChanged.';

-- Seller's own list, newest first, and the bulk seller_active update on a status change.
CREATE INDEX idx_products_organization_id ON products (organization_id, id);
-- Storefront, newest first: only what customers can see is indexed.
CREATE INDEX idx_products_storefront ON products (id) WHERE status = 'ACTIVE' AND seller_active;
-- Storefront by category; also "is this category in use?".
CREATE INDEX idx_products_category_id ON products (category_id, id);
CREATE INDEX idx_products_created_by ON products (created_by);

-- ---------------------------------------------------------------------
-- 3. Platform permission
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, description) VALUES
    ('catalog:manage', 'Create, rename and delete product categories');

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'catalog:manage' FROM roles r WHERE r.code = 'ADMIN';
