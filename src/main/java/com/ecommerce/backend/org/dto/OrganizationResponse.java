package com.ecommerce.backend.org.dto;

import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.OrganizationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An organisation as one of its members sees it. The internal status reason is not part of it:
 * a suspended seller is told only that it is suspended.
 */
@Schema(description = "An organisation, with the caller's role in it")
public record OrganizationResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "acme-store") String slug,
        @Schema(example = "Acme Store") String name,
        @Schema(example = "PENDING_APPROVAL") OrganizationStatus status,
        @Schema(example = "OWNER") OrgRole myRole,
        @Schema(example = "[\"ORGANIZATION_READ\", \"ORGANIZATION_UPDATE\"]",
                description = "What the caller may do here; lets a client hide controls it cannot use")
        List<OrgPermission> myPermissions,
        @Schema(example = "0", description = "Send it back when updating") long version,
        Instant createdAt,
        Instant updatedAt) {

    public static OrganizationResponse from(Organization organization, OrgRole role) {
        return new OrganizationResponse(organization.getId(), organization.getSlug(), organization.getName(),
                organization.getStatus(), role, role.permissions().stream().sorted().toList(),
                organization.getVersion(), organization.getCreatedAt(), organization.getUpdatedAt());
    }
}
