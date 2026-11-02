package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.repository.UserSummary;
import com.ecommerce.backend.rbac.service.UserAuthorities;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The signed-in user as returned by {@code GET /me}.
 *
 * <p>An explicit allow-list of fields. Adding a column to {@code users} never leaks it here by
 * accident, which is the main reason APIs must not return entities.
 *
 * <p>{@code roles} and {@code permissions} come from the database, not the token, so a frontend
 * sees changes immediately and can show or hide features. The server still enforces every check.
 */
@Schema(description = "The signed-in account")
public record MeResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "alice@example.com") String email,
        @Schema(example = "ACTIVE") UserStatus status,
        @Schema(example = "true") boolean emailVerified,
        @Schema(example = "2026-10-29T07:27:40Z") Instant lastLoginAt,
        @Schema(example = "2026-10-01T09:00:00Z") Instant createdAt,
        @Schema(example = "[\"CUSTOMER\"]") List<String> roles,
        @Schema(example = "[]") List<String> permissions) {

    public static MeResponse from(UserSummary summary, UserAuthorities authorities) {
        return new MeResponse(summary.id(), summary.email(), summary.status(),
                summary.emailVerifiedAt() != null, summary.lastLoginAt(), summary.createdAt(),
                List.copyOf(authorities.roles()), List.copyOf(authorities.permissions()));
    }
}
