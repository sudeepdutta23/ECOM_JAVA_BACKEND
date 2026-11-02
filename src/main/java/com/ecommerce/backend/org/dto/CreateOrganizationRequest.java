package com.ecommerce.backend.org.dto;

import com.ecommerce.backend.org.entity.Organization;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/organizations}. Status and owner are not fields: the server decides them. */
@Schema(description = "A new seller organisation")
public record CreateOrganizationRequest(
        @Schema(example = "acme-store", description = "Public handle in storefront URLs; immutable")
        @NotBlank
        @Pattern(regexp = Organization.SLUG_REGEX,
                message = "must be 3 to 50 lower-case letters, digits or hyphens, not starting or ending with a hyphen")
        String slug,

        @Schema(example = "Acme Store")
        @NotBlank @Size(max = 100)
        String name) {

    /** Normalized before validation: " Acme-Store" is accepted as "acme-store". */
    public CreateOrganizationRequest {
        slug = slug == null ? null : Organization.normalizeSlug(slug);
        name = name == null ? null : name.strip();
    }
}
