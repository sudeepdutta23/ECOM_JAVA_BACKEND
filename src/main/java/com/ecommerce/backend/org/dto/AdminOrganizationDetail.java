package com.ecommerce.backend.org.dto;

import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.entity.Organization;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** An organisation as platform staff see it, including the internal status reason. */
@Schema(description = "An organisation as seen by platform staff")
public record AdminOrganizationDetail(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "acme-store") String slug,
        @Schema(example = "Acme Store") String name,
        @Schema(example = "SUSPENDED") OrganizationStatus status,
        @Schema(example = "Counterfeit goods, case 4711", description = "Internal note; never shown to members")
        String statusReason,
        Instant statusChangedAt,
        UUID statusChangedBy,
        UUID createdBy,
        @Schema(example = "4") long members,
        @Schema(example = "1") long owners,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminOrganizationDetail from(Organization organization, long members, long owners) {
        return new AdminOrganizationDetail(organization.getId(), organization.getSlug(), organization.getName(),
                organization.getStatus(), organization.getStatusReason(), organization.getStatusChangedAt(),
                organization.getStatusChangedBy(), organization.getCreatedBy(), members, owners,
                organization.getCreatedAt(), organization.getUpdatedAt());
    }
}
