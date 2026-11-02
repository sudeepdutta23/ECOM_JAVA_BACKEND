package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.rbac.service.UserAuthorities;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything an administrator needs to judge an account, as an explicit allow-list: the password
 * hash and token data are never part of it, whatever columns {@code users} gains later.
 */
@Schema(description = "An account as seen by an administrator")
public record AdminUserDetail(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "alice@example.com") String email,
        @Schema(example = "SUSPENDED") UserStatus status,
        @Schema(example = "Chargeback fraud, ticket 4711", description = "Internal note; never shown to the owner")
        String statusReason,
        Instant statusChangedAt,
        @Schema(description = "Who made the latest status change; the owner's own ID for self-deactivation")
        UUID statusChangedBy,
        Instant emailVerifiedAt,
        Instant lastLoginAt,
        @Schema(example = "0", description = "Consecutive wrong passwords") int failedLoginCount,
        @Schema(description = "Set only while the account is temporarily locked") Instant lockedUntil,
        @Schema(example = "2", description = "Signed-in devices (refresh-token families still valid)") long activeSessions,
        @Schema(example = "[\"CUSTOMER\"]") List<String> roles,
        @Schema(example = "[]") List<String> permissions,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminUserDetail from(User user, UserAuthorities authorities, long activeSessions, Instant now) {
        return new AdminUserDetail(user.getId(), user.getEmail(), user.getStatus(), user.getStatusReason(),
                user.getStatusChangedAt(), user.getStatusChangedBy(), user.getEmailVerifiedAt(),
                user.getLastLoginAt(), user.getFailedLoginCount(),
                user.isLockedAt(now) ? user.getLockedUntil() : null, // an expired lock is no longer a lock
                activeSessions, List.copyOf(authorities.roles()), List.copyOf(authorities.permissions()),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
