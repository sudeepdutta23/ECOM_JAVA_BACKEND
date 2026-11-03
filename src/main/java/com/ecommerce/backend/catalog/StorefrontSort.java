package com.ecommerce.backend.catalog;

/**
 * The orders a storefront search can be sorted in. A fixed list: the SQL for each is written in
 * the repository, so no request value ever becomes part of an ORDER BY.
 */
public enum StorefrontSort {
    /** Best match first; only meaningful with a search text, otherwise newest. */
    RELEVANCE,
    NEWEST,
    PRICE_ASC,
    PRICE_DESC
}
