package com.ecommerce.backend.catalog.dto;

import com.ecommerce.backend.common.validation.Inputs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of {@code POST} and {@code PUT /api/v1/organizations/{orgId}/products[/{productId}]}.
 * No currency (the platform has one), no stock (changed only by atomic adjustments), no status
 * (changed by the publish and archive commands), no organisation (from the URL, checked by the guard).
 */
@Schema(description = "A product as the seller edits it")
public record ProductRequest(
        @Schema(example = "MUG-001", description = "Your article number, unique in your shop; stored upper-case")
        @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9._-]{0,63}$",
                message = "must be 1 to 64 letters, digits, dots, hyphens or underscores")
        String sku,

        @Schema(example = "Enamel mug, blue")
        @NotBlank @Size(max = 200) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String name,

        @Schema(example = "Hand-made enamel mug, 350 ml.")
        @Size(max = 5000) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS_MULTILINE, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String description,

        @Schema(example = "19.99", description = "In the platform currency, at most two decimal places")
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") @Digits(integer = 7, fraction = 2)
        BigDecimal price,

        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415",
                description = "Optional; a category from GET /api/v1/catalog/categories")
        UUID categoryId,

        @Schema(example = "0", description = "Required when replacing: the version you read")
        Long version) {

    public ProductRequest {
        sku = Inputs.code(sku);
        name = Inputs.text(name);
        description = Inputs.text(description);
    }
}
