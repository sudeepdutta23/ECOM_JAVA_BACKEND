package com.ecommerce.backend.rbac.dto;

import com.ecommerce.backend.rbac.entity.PermissionDefinition;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A permission in the catalogue")
public record PermissionResponse(
        @Schema(example = "role:read") String code,
        @Schema(example = "View roles, permissions and the roles of a user") String description) {

    public static PermissionResponse from(PermissionDefinition definition) {
        return new PermissionResponse(definition.getCode(), definition.getDescription());
    }
}
