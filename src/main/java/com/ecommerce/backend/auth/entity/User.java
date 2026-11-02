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
import java.util.UUID;
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

    /**
     * Stored instead of a hash when an account is erased. No encoder accepts it, and login never
     * reaches the password check for an erased account anyway ({@code AuthenticationService}).
     */
    static final String ERASED_PASSWORD_HASH = "{erased}";

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

    /** Internal note for the latest status change; required for SUSPENDED. Never shown to the owner or logged. */
    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    /** Who made the latest status change: the owner (deactivation) or an administrator. */
    @Column(name = "status_changed_by")
    private UUID statusChangedBy;

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

    /** The owner closes their own account. */
    public void deactivate(Instant now) {
        changeStatus(UserStatus.DEACTIVATED, null, getId(), now);
    }

    /**
     * An administrator blocks the account. Only active accounts can be suspended: a pending one
     * cannot sign in anyway, and its verification link could then never apply.
     */
    public void suspend(String reason, UUID by, Instant now) {
        requireStatus(UserStatus.ACTIVE);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A suspension needs a reason");
        }
        changeStatus(UserStatus.SUSPENDED, reason.strip(), Objects.requireNonNull(by, "by"), now);
    }

    /** An administrator reopens a suspended or self-deactivated account. */
    public void reactivate(UUID by, Instant now) {
        requireStatus(UserStatus.SUSPENDED, UserStatus.DEACTIVATED);
        changeStatus(UserStatus.ACTIVE, null, Objects.requireNonNull(by, "by"), now);
    }

    /**
     * Anonymises the account: the row stays (other tables will reference it), the personal data
     * goes. Allowed from every status except ERASED, and irreversible. Sessions, email links and
     * roles live in other tables; {@code AccountEraser} deletes them in the same transaction.
     *
     * @param by the owner's own ID, or the administrator who handled the request
     */
    public void erase(UUID by, Instant now) {
        if (status == UserStatus.ERASED) {
            throw new IllegalStateException("Account is already erased");
        }
        this.email = erasedEmail(getId());
        this.passwordHash = ERASED_PASSWORD_HASH;
        this.emailVerifiedAt = null;
        this.lastLoginAt = null;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        changeStatus(UserStatus.ERASED, null, Objects.requireNonNull(by, "by"), now);
    }

    public boolean isErased() {
        return status == UserStatus.ERASED;
    }

    /**
     * Unique (contains the ID) and undeliverable (RFC 2606 reserves {@code .invalid}), so the original
     * address is free to register again. Must match {@code ck_users_erased_is_anonymous} (V5).
     */
    static String erasedEmail(UUID id) {
        return "erased-" + id + "@erased.invalid";
    }

    /**
     * Addresses under the {@code .invalid} top-level domain can never receive mail (RFC 2606), so no
     * real person registers one. Refusing them also keeps erasure placeholders unclaimable: someone
     * who registered {@code erased-<your id>@erased.invalid} would make your erasure fail on the
     * unique email index. V6 enforces the narrower rule in the database as well.
     *
     * @param normalizedEmail output of {@link #normalizeEmail}
     */
    public static boolean isReservedEmail(String normalizedEmail) {
        return normalizedEmail.endsWith(".invalid");
    }

    private void requireStatus(UserStatus... allowed) {
        for (UserStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Status " + status + " does not allow this change");
    }

    private void changeStatus(UserStatus newStatus, String reason, UUID by, Instant now) {
        this.status = newStatus;
        this.statusReason = reason;
        this.statusChangedBy = by;
        this.statusChangedAt = Objects.requireNonNull(now, "now");
    }

    /** Never include the password hash or email: toString ends up in logs. */
    @Override
    public String toString() {
        return "User[id=" + getId() + ", status=" + status + "]";
    }
}
