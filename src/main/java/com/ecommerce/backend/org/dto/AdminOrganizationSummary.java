package com.ecommerce.backend.org.dto;

import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.entity.Organization;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** One row of the platform's organisation list. */
@Schema(description = "An organisation in the platform list")
public record AdminOrganizationSummary(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "acme-store") String slug,
        @Schema(example = "Acme Store") String name,
        @Schema(example = "PENDING_APPROVAL") OrganizationStatus status,
        UUID createdBy,
        Instant createdAt) {

    public static AdminOrganizationSummary from(Organization organization) {
        return new AdminOrganizationSummary(organization.getId(), organization.getSlug(), organization.getName(),
                organization.getStatus(), organization.getCreatedBy(), organization.getCreatedAt());
    }
}
