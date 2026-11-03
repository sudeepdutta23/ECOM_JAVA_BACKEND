package com.ecommerce.backend.catalog.dto;

import com.ecommerce.backend.catalog.entity.Category;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "A product category")
public record CategoryResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "books") String slug,
        @Schema(example = "Books") String name,
        @Schema(example = "0") long version) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getSlug(), category.getName(), category.getVersion());
    }
}
