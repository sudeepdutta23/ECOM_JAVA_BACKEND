package com.ecommerce.backend.catalog.dto;

import com.ecommerce.backend.catalog.entity.ProductImage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A product image as its seller sees it. The storage key stays internal. */
@Schema(description = "A product image")
public record ProductImageResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "image/jpeg") String contentType,
        @Schema(example = "1200") int width,
        @Schema(example = "900") int height,
        @Schema(example = "184320") int sizeBytes,
        @Schema(description = "Public URL once the product is on sale")
        String url) {

    public static ProductImageResponse from(ProductImage image) {
        return new ProductImageResponse(image.getId(), image.getContentType(), image.getWidth(), image.getHeight(),
                image.getSizeBytes(), publicUrl(image));
    }

    public static String publicUrl(ProductImage image) {
        return "/api/v1/catalog/products/" + image.getProductId() + "/images/" + image.getId();
    }
}
