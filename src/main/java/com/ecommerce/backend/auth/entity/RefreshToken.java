package com.ecommerce.backend.auth.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A refresh token. Only its SHA-256 hash is stored; the raw value exists only in the client.
 *
 * <p>The {@code user} association is LAZY: refreshing a token needs the user's ID, which a
 * Hibernate proxy can return without loading the row. EAGER would add a join to every lookup.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseEntity {

    static final int MAX_IP_LENGTH = 45;
    static final int MAX_USER_AGENT_LENGTH = 255;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 32)
    private RevokeReason revokeReason;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "created_ip", length = MAX_IP_LENGTH, updatable = false)
    private String createdIp;

    @Column(name = "user_agent", length = MAX_USER_AGENT_LENGTH, updatable = false)
    private String userAgent;

    /**
     * Issues a token. A new login starts a new family; a rotation passes the old token's family.
     */
    public static RefreshToken issue(User user, UUID familyId, String tokenHash,
                                     Instant now, Duration ttl, String ip, String userAgent) {
        RefreshToken token = new RefreshToken();
        token.user = Objects.requireNonNull(user, "user");
        token.familyId = Objects.requireNonNull(familyId, "familyId");
        token.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        token.issuedAt = now;
        token.expiresAt = now.plus(ttl);
        // Client-controlled headers: truncate rather than fail on oversized values.
        token.createdIp = truncate(ip, MAX_IP_LENGTH);
        token.userAgent = truncate(userAgent, MAX_USER_AGENT_LENGTH);
        return token;
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Idempotent: the first reason is kept, because it is the one that explains what happened. */
    public void revoke(RevokeReason reason, Instant now) {
        if (revokedAt != null) {
            return;
        }
        this.revokedAt = now;
        this.revokeReason = Objects.requireNonNull(reason, "reason");
    }

    /** Marks this token as replaced by its successor during rotation. */
    public void markRotated(UUID successorId, Instant now) {
        revoke(RevokeReason.ROTATED, now);
        this.replacedById = successorId;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public String toString() {
        return "RefreshToken[id=" + getId() + ", familyId=" + familyId + ", revokeReason=" + revokeReason + "]";
    }
}
