package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** One row of the admin user list. Deliberately small; the detail view has the rest. */
@Schema(description = "An account in the admin user list")
public record AdminUserSummary(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "alice@example.com") String email,
        @Schema(example = "ACTIVE") UserStatus status,
        @Schema(example = "true") boolean emailVerified,
        @Schema(example = "false", description = "Temporarily locked after failed logins") boolean locked,
        Instant lastLoginAt,
        Instant createdAt) {

    public static AdminUserSummary from(User user, Instant now) {
        return new AdminUserSummary(user.getId(), user.getEmail(), user.getStatus(), user.isEmailVerified(),
                user.isLockedAt(now), user.getLastLoginAt(), user.getCreatedAt());
    }
}
