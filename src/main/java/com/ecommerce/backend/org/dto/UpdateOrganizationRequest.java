package com.ecommerce.backend.org.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/organizations/{orgId}}. No {@code slug}: it is immutable, and unknown
 * fields are rejected, so sending one fails loudly.
 */
@Schema(description = "New settings of an organisation")
public record UpdateOrganizationRequest(
        @Schema(example = "Acme Store Berlin")
        @NotBlank @Size(max = 100)
        String name,

        @Schema(example = "0", description = "Version from the last read; protects against lost updates")
        @NotNull
        Long version) {

    public UpdateOrganizationRequest {
        name = name == null ? null : name.strip();
    }
}
