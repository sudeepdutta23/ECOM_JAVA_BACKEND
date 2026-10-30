package com.ecommerce.backend.auth.repository;

import com.ecommerce.backend.auth.entity.UserStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a user for {@code GET /me}.
 *
 * <p><b>Why a projection instead of loading the entity?</b> The query selects only these columns,
 * so the password hash never even leaves the database for this endpoint. The result is not a
 * managed entity, so there is no dirty checking and no risk of an accidental update.
 */
public record UserSummary(
        UUID id,
        String email,
        UserStatus status,
        Instant emailVerifiedAt,
        Instant lastLoginAt,
        Instant createdAt) {
}
