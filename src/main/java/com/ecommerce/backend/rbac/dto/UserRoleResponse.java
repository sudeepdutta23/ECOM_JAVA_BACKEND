package com.ecommerce.backend.rbac.dto;

import com.ecommerce.backend.rbac.repository.UserRoleView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A role held by a user")
public record UserRoleResponse(
        @Schema(example = "01a18700-0000-7000-8000-000000000001") UUID roleId,
        @Schema(example = "ORDER_MANAGER") String code,
        @Schema(example = "Order manager") String name,
        @Schema(example = "false") boolean systemRole,
        Instant assignedAt,
        @Schema(description = "Administrator who granted it; null when granted by the system") UUID assignedBy) {

    public static UserRoleResponse from(UserRoleView view) {
        return new UserRoleResponse(view.roleId(), view.code(), view.name(), view.systemRole(),
                view.assignedAt(), view.assignedBy());
    }
}
