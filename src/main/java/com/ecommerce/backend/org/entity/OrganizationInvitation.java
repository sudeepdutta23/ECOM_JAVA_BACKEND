package com.ecommerce.backend.org.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import com.ecommerce.backend.org.OrgRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An offer to join an organisation, sent to an email address. Single use; only the hash of the
 * link token is stored. Ends by acceptance, revocation (including replacement by a newer
 * invitation to the same address) or expiry.
 */
@Entity
@Table(name = "organization_invitations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationInvitation extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "email", nullable = false, updatable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false, length = 32)
    private OrgRole role;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    /** Null once that member's account was deleted. */
    @Column(name = "invited_by", updatable = false)
    private UUID invitedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** @param email normalized ({@link #normalizeEmail}) */
    public static OrganizationInvitation issue(UUID organizationId, String email, OrgRole role, String tokenHash,
                                               UUID invitedBy, Instant now, Duration ttl) {
        OrganizationInvitation invitation = new OrganizationInvitation();
        invitation.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        invitation.email = Objects.requireNonNull(email, "email");
        invitation.role = Objects.requireNonNull(role, "role");
        invitation.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        invitation.invitedBy = invitedBy;
        invitation.createdAt = Objects.requireNonNull(now, "now");
        invitation.expiresAt = now.plus(ttl);
        return invitation;
    }

    /** The same normalization as account emails, so "Bob@Example.com " can accept an invitation to bob@example.com. */
    public static String normalizeEmail(String rawEmail) {
        return Objects.requireNonNull(rawEmail, "email").strip().toLowerCase(Locale.ROOT);
    }

    public boolean isOpenAt(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    public void accept(Instant now) {
        if (!isOpenAt(now)) {
            throw new IllegalStateException("Invitation is not open");
        }
        this.acceptedAt = now;
    }

    /** Idempotent: revoking an accepted, revoked or expired invitation changes nothing. */
    public void revoke(Instant now) {
        if (acceptedAt == null && revokedAt == null) {
            this.revokedAt = now;
        }
    }

    /** Never the email or the token hash: toString ends up in logs. */
    @Override
    public String toString() {
        return "OrganizationInvitation[id=" + getId() + ", organizationId=" + organizationId + ", role=" + role + "]";
    }
}
