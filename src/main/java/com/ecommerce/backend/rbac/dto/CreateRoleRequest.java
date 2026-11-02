package com.ecommerce.backend.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** Body of {@code POST /api/v1/admin/roles}. */
@Schema(description = "A new custom role")
public record CreateRoleRequest(
        @Schema(example = "ORDER_MANAGER", description = "Upper snake case; immutable once created")
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$", message = "must be UPPER_SNAKE_CASE, 2 to 50 characters")
        String code,

        @Schema(example = "Order manager")
        @NotBlank @Size(max = 100)
        String name,

        @Schema(example = "Handles refunds and order issues")
        @Size(max = 255)
        String description,

        @Schema(example = "[\"role:read\"]", description = "Permission codes; may be empty")
        @NotNull @Size(max = 100)
        Set<@NotBlank String> permissions) {

    public CreateRoleRequest {
        name = name == null ? null : name.strip();
    }
}
