package com.ecommerce.backend.rbac.dto;

import com.ecommerce.backend.rbac.Permission;
import com.ecommerce.backend.rbac.entity.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A role and the permissions it grants")
public record RoleResponse(
        @Schema(example = "01a18700-0000-7000-8000-000000000001") UUID id,
        @Schema(example = "ORDER_MANAGER") String code,
        @Schema(example = "Order manager") String name,
        @Schema(example = "Handles refunds and order issues") String description,
        @Schema(example = "false", description = "System roles are managed by migrations and read-only") boolean systemRole,
        @Schema(example = "[\"role:read\"]") List<String> permissions,
        @Schema(example = "0", description = "Send back on update") long version,
        Instant createdAt,
        Instant updatedAt) {

    public static RoleResponse from(Role role) {
        return new RoleResponse(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                role.isSystemRole(), role.getPermissions().stream().map(Permission::code).sorted().toList(),
                role.getVersion(), role.getCreatedAt(), role.getUpdatedAt());
    }
}
