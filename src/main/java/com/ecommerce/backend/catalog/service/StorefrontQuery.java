package com.ecommerce.backend.catalog.service;

import com.ecommerce.backend.catalog.StorefrontSort;
import java.math.BigDecimal;

/**
 * A storefront search as the customer asked for it. Every filter is optional.
 *
 * @param text free text, searched in names (weighted higher) and descriptions; null for none
 * @param sort null means relevance with a text, newest without
 */
public record StorefrontQuery(String text, String categorySlug, String sellerSlug, BigDecimal minPrice,
                              BigDecimal maxPrice, boolean inStockOnly, StorefrontSort sort, int page, int size) {

    public StorefrontQuery {
        text = text == null || text.isBlank() ? null : text.strip();
    }

    StorefrontSort effectiveSort() {
        if (sort == null || (sort == StorefrontSort.RELEVANCE && text == null)) {
            return text == null ? StorefrontSort.NEWEST : StorefrontSort.RELEVANCE;
        }
        return sort;
    }
}
