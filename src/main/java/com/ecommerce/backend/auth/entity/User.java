package com.ecommerce.backend.auth.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * An account: identity, credentials and login-protection state.
 *
 * <p><b>Design rules</b>
 * <ul>
 *   <li>No public setters. State changes only through intention-revealing methods such as
 *       {@link #verifyEmail}, so invariants live in one place.</li>
 *   <li>The protected no-arg constructor exists only for Hibernate.</li>
 *   <li>The failed-login counter is incremented by an atomic SQL update in the repository,
 *       not here, so concurrent failed logins cannot lose increments.</li>
 * </ul>
 */
@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    /** Optimistic lock: a stale concurrent update fails instead of silently overwriting. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ------------------------------------------------------------------ factory

    /**
     * Creates a new, unverified account.
     *
     * @param email        raw email; normalized here so every caller stores the same form
     * @param passwordHash already-encoded hash; this class never sees plain passwords
     */
    public static User register(String email, String passwordHash, Instant now) {
        User user = new User();
        user.email = normalizeEmail(email);
        user.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        user.status = UserStatus.PENDING_VERIFICATION;
        user.failedLoginCount = 0;
        user.passwordChangedAt = Objects.requireNonNull(now, "now");
        return user;
    }

    /** Trim and lower-case with a fixed locale (Turkish "I" would otherwise break lookups). */
    public static String normalizeEmail(String rawEmail) {
        return Objects.requireNonNull(rawEmail, "email").strip().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ behaviour

    public void verifyEmail(Instant now) {
        if (status != UserStatus.PENDING_VERIFICATION) {
            throw new IllegalStateException("Only pending accounts can be verified, status=" + status);
        }
        this.status = UserStatus.ACTIVE;
        this.emailVerifiedAt = now;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Clears lockout state after a correct password. */
    public void recordSuccessfulLogin(Instant now) {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = now;
    }

    public void changePassword(String newPasswordHash, Instant now) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "newPasswordHash");
        this.passwordChangedAt = now;
        // A successful reset proves ownership, so it also ends any lockout.
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public void deactivate() {
        this.status = UserStatus.DEACTIVATED;
    }

    /** Never include the password hash or email: toString ends up in logs. */
    @Override
    public String toString() {
        return "User[id=" + getId() + ", status=" + status + "]";
    }
}
