package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.repository.UserSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * The signed-in user as returned by {@code GET /me}.
 *
 * <p>An explicit allow-list of fields. Adding a column to {@code users} never leaks it here by
 * accident, which is the main reason APIs must not return entities.
 */
@Schema(description = "The signed-in account")
public record MeResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "alice@example.com") String email,
        @Schema(example = "ACTIVE") UserStatus status,
        @Schema(example = "true") boolean emailVerified,
        @Schema(example = "2026-10-29T07:27:40Z") Instant lastLoginAt,
        @Schema(example = "2026-10-01T09:00:00Z") Instant createdAt) {

    public static MeResponse from(UserSummary summary) {
        return new MeResponse(summary.id(), summary.email(), summary.status(),
                summary.emailVerifiedAt() != null, summary.lastLoginAt(), summary.createdAt());
    }
}
