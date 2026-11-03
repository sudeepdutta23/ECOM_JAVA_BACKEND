package com.ecommerce.backend.catalog.dto;

import com.ecommerce.backend.catalog.ProductStatus;
import com.ecommerce.backend.catalog.entity.Product;
import com.ecommerce.backend.common.util.MinorUnits;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A product as its seller sees it, stock and status included. */
@Schema(description = "A product, seller view")
public record ProductResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "MUG-001") String sku,
        @Schema(example = "Enamel mug, blue") String name,
        String description,
        @Schema(example = "19.99") BigDecimal price,
        @Schema(example = "EUR") String currency,
        @Schema(example = "25") int stock,
        @Schema(example = "DRAFT") ProductStatus status,
        @Schema(description = "Whether customers can see it now: ACTIVE, and the seller is approved and not suspended")
        boolean onSale,
        UUID categoryId,
        @Schema(example = "0", description = "Send it back when replacing") long version,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getSku(), product.getName(), product.getDescription(),
                MinorUnits.toAmount(product.getPriceMinor()), product.getCurrency(), product.getStock(),
                product.getStatus(), product.isOnSale(), product.getCategoryId(), product.getVersion(),
                product.getCreatedAt(), product.getUpdatedAt());
    }
}
