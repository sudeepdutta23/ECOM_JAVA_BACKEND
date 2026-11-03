package com.ecommerce.backend.catalog.dto;

import com.ecommerce.backend.catalog.entity.Category;
import com.ecommerce.backend.catalog.entity.Product;
import com.ecommerce.backend.common.util.MinorUnits;
import com.ecommerce.backend.org.SellerInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A product as customers see it. No stock count (a competitor could track sales with it), no SKU,
 * no status: only whether it can be bought.
 */
@Schema(description = "A product on the storefront")
public record StorefrontProductResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "Enamel mug, blue") String name,
        String description,
        @Schema(example = "19.99") BigDecimal price,
        @Schema(example = "EUR") String currency,
        @Schema(example = "true") boolean inStock,
        CategoryRef category,
        SellerRef seller) {

    public record CategoryRef(@Schema(example = "books") String slug, @Schema(example = "Books") String name) {
    }

    public record SellerRef(@Schema(example = "acme-store") String slug, @Schema(example = "Acme Store") String name) {
    }

    /** @param category null if the product has none */
    public static StorefrontProductResponse from(Product product, Category category, SellerInfo seller) {
        return new StorefrontProductResponse(product.getId(), product.getName(), product.getDescription(),
                MinorUnits.toAmount(product.getPriceMinor()), product.getCurrency(), product.getStock() > 0,
                category == null ? null : new CategoryRef(category.getSlug(), category.getName()),
                new SellerRef(seller.slug(), seller.name()));
    }
}
