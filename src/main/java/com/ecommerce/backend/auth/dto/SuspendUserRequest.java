package com.ecommerce.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Why the account is suspended. Internal: shown to administrators, never to the owner.")
public record SuspendUserRequest(
        @Schema(example = "Chargeback fraud, ticket 4711") @NotBlank @Size(max = 500) String reason) {
}
