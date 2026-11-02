package com.ecommerce.backend.org.dto;

import com.ecommerce.backend.org.OrgRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/v1/organizations/{orgId}/members/{userId}}. */
@Schema(description = "The member's new role")
public record ChangeMemberRoleRequest(
        @Schema(example = "MANAGER")
        @NotNull
        OrgRole role) {
}
