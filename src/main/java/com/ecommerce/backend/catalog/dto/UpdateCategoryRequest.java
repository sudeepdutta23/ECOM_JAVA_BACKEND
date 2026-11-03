package com.ecommerce.backend.catalog.dto;

import com.ecommerce.backend.common.validation.Inputs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code PUT /api/v1/admin/categories/{categoryId}}. The slug never changes. */
@Schema(description = "A new name for a category")
public record UpdateCategoryRequest(
        @Schema(example = "Books and e-books")
        @NotBlank @Size(max = 100) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String name,

        @Schema(example = "0", description = "Version from the last read")
        @NotNull
        Long version) {

    public UpdateCategoryRequest {
        name = Inputs.text(name);
    }
}
