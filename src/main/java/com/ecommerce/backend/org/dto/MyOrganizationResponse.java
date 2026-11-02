package com.ecommerce.backend.org.dto;

import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.repository.MembershipView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** One row of "my organisations". */
@Schema(description = "An organisation the caller works for")
public record MyOrganizationResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "acme-store") String slug,
        @Schema(example = "Acme Store") String name,
        @Schema(example = "ACTIVE") OrganizationStatus status,
        @Schema(example = "OWNER") OrgRole myRole,
        Instant joinedAt) {

    public static MyOrganizationResponse from(MembershipView view) {
        return new MyOrganizationResponse(view.organizationId(), view.slug(), view.name(), view.status(),
                view.role(), view.joinedAt());
    }
}
