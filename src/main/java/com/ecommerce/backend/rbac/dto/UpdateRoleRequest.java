package com.ecommerce.backend.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Body of {@code PUT /api/v1/admin/roles/{roleId}}: the full new state of the role.
 *
 * <p>No {@code code} field: codes are immutable, and unknown fields are rejected, so sending one
 * fails loudly instead of being silently ignored.
 *
 * <p><b>{@code version}</b> is the value from the GET the client edited. If someone saved a change
 * in between, the server answers 409 instead of overwriting their work (optimistic locking).
 * The HTTP-native alternative is an {@code ETag} response header plus an {@code If-Match} request
 * header; a body field is simpler for clients and works the same way.
 */
@Schema(description = "New name, description and complete permission set of a role")
public record UpdateRoleRequest(
        @Schema(example = "Order manager")
        @NotBlank @Size(max = 100)
        String name,

        @Schema(example = "Handles refunds and order issues")
        @Size(max = 255)
        String description,

        @Schema(example = "[\"role:read\"]")
        @NotNull @Size(max = 100)
        Set<@NotBlank String> permissions,

        @Schema(example = "0", description = "Version from the last read; protects against lost updates")
        @NotNull
        Long version) {

    public UpdateRoleRequest {
        name = name == null ? null : name.strip();
    }
}
