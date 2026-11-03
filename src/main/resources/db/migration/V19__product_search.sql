-- =====================================================================
-- V19: Storefront search (Phase 10)
--
-- Full-text search in PostgreSQL itself: a generated tsvector (name weighted above description),
-- kept up to date by the database on every insert and update, and a GIN index over what is on
-- sale. No search server to run or keep in sync until the catalogue outgrows this (ADR-014).
--
-- 'simple' configuration: lower-casing and splitting, no language-specific stemming, because
-- sellers write in many languages. Design: docs/modules/SEARCH.md.
-- =====================================================================

ALTER TABLE products ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('simple', coalesce(name, '')), 'A') ||
    setweight(to_tsvector('simple', coalesce(description, '')), 'B')
) STORED;

-- Text search over what customers can see. Partial, like idx_products_storefront.
CREATE INDEX idx_products_search ON products USING GIN (search_vector) WHERE status = 'ACTIVE' AND seller_active;

-- Sorting and filtering by price over what is on sale; id breaks ties so pages are stable.
CREATE INDEX idx_products_storefront_price ON products (price_minor, id) WHERE status = 'ACTIVE' AND seller_active;
