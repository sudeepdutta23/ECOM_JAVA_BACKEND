package com.ecommerce.backend.catalog;

/**
 * A product's life cycle. Values must match {@code ck_products_status} (V13).
 *
 * <pre>
 * DRAFT --publish--> ACTIVE --archive--> ARCHIVED --publish--> ACTIVE
 *   └------------------archive-------------^
 * </pre>
 * Only ACTIVE products of ACTIVE sellers are on the storefront. Nothing is ever deleted: orders
 * (step 6.3) keep their own copy of name and price, but a product page must stay resolvable.
 */
public enum ProductStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
