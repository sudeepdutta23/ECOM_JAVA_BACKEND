-- =====================================================================
-- V17: Product images (Phase 8)
--
-- Metadata only; the bytes live in FileStorage under storage_key. Every image was decoded and
-- re-encoded on upload, so content_type, width and height describe what is actually stored.
--
-- Design: docs/modules/FILES.md.
-- =====================================================================

CREATE TABLE product_images (
    id              UUID         NOT NULL,
    product_id      UUID         NOT NULL,
    -- Tenant column like every seller-owned table (ADR-012): lookups take it.
    organization_id UUID         NOT NULL,
    -- Made by the application, never from the uploaded file name.
    storage_key     VARCHAR(200) NOT NULL,
    content_type    VARCHAR(20)  NOT NULL,
    size_bytes      INTEGER      NOT NULL,
    width           INTEGER      NOT NULL,
    height          INTEGER      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_product_images PRIMARY KEY (id),
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_images_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uk_product_images_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_product_images_content_type CHECK (content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT ck_product_images_size CHECK (size_bytes BETWEEN 1 AND 10485760),
    CONSTRAINT ck_product_images_dimensions CHECK (width BETWEEN 1 AND 6000 AND height BETWEEN 1 AND 6000)
);

COMMENT ON TABLE product_images IS 'Product image metadata; bytes in FileStorage. Owned by the catalog module.';

-- A product's images in upload order (UUID v7), for the seller and the storefront.
CREATE INDEX idx_product_images_product_id ON product_images (product_id, id);
CREATE INDEX idx_product_images_organization_id ON product_images (organization_id);
