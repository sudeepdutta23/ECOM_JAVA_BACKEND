package com.ecommerce.backend.catalog.repository;

import com.ecommerce.backend.catalog.ProductStatus;
import com.ecommerce.backend.catalog.entity.Product;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * What customers may see, with only the filters in use: ACTIVE products of ACTIVE sellers.
 * Matches {@code idx_products_storefront} and {@code idx_products_category_id}.
 */
public final class StorefrontSearch {

    private StorefrontSearch() {
    }

    /** @param categoryId null for every category; @param organizationId null for every seller */
    public static Specification<Product> onSale(UUID categoryId, UUID organizationId) {
        return (root, query, cb) -> {
            var predicate = cb.and(cb.equal(root.get("status"), ProductStatus.ACTIVE), cb.isTrue(root.get("sellerActive")));
            if (categoryId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("categoryId"), categoryId));
            }
            if (organizationId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("organizationId"), organizationId));
            }
            return predicate;
        };
    }
}
