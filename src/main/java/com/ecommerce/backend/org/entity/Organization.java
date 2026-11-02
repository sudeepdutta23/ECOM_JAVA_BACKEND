package com.ecommerce.backend.org.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.OrganizationStatus;
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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A seller on the marketplace: the tenant that will own catalogue and orders (Phase 6).
 *
 * <p>Users are referenced by ID only, never as a JPA association to {@code auth.entity.User}: the
 * org module does not depend on auth's entities. The foreign keys keep integrity.
 *
 * <p>Status transitions are methods with their own preconditions, like {@code User}:
 * <pre>
 * PENDING_APPROVAL --approve--> ACTIVE &lt;--reactivate-- SUSPENDED
 *                                  └-------suspend--------^
 * any status except CLOSED --close--> CLOSED (terminal)
 * </pre>
 */
@Entity
@Table(name = "organizations")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization extends BaseEntity {

    /** Must match {@code ck_organizations_slug_format} (V7). A string too, for {@code @Pattern}. */
    public static final String SLUG_REGEX = "^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$";
    public static final Pattern SLUG_PATTERN = Pattern.compile(SLUG_REGEX);

    /**
     * Handles a seller could use to look like the platform itself ("support", "official") or that
     * would collide with storefront routes. Checked at creation; existing slugs are never renamed.
     */
    public static final Set<String> RESERVED_SLUGS = Set.of(
            "admin", "administrator", "api", "help", "support", "official", "marketplace", "platform",
            "security", "staff", "system", "www", "sellers", "store", "shop", "checkout", "account");

    @Column(name = "slug", nullable = false, updatable = false, length = 50)
    private String slug;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrganizationStatus status;

    /** Internal note for the latest status change; required for SUSPENDED. Never shown to members or logged. */
    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "status_changed_by")
    private UUID statusChangedBy;

    /** The founding owner. Null after that user was deleted; erasure keeps the (anonymous) ID. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** Two people changing the organisation at once: the second write fails instead of overwriting. */
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
     * A new seller, waiting for platform approval. The caller adds the founder as {@link OrgRole#OWNER}
     * in the same transaction, so an organisation is never without an owner.
     *
     * @param slug already normalized ({@link #normalizeSlug}) and validated
     */
    public static Organization create(String slug, String name, UUID createdBy, Instant now) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException("Invalid slug");
        }
        Organization organization = new Organization();
        organization.slug = slug;
        organization.name = requireName(name);
        organization.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        organization.changeStatus(OrganizationStatus.PENDING_APPROVAL, null, createdBy, now);
        return organization;
    }

    /** Trim and lower-case with a fixed locale, so "Acme-Store " and "acme-store" are the same slug. */
    public static String normalizeSlug(String rawSlug) {
        return Objects.requireNonNull(rawSlug, "slug").strip().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ behaviour

    public void rename(String newName) {
        requireNotClosed();
        this.name = requireName(newName);
    }

    /** The platform lets a pending seller start selling. */
    public void approve(UUID by, Instant now) {
        requireStatus(OrganizationStatus.PENDING_APPROVAL);
        changeStatus(OrganizationStatus.ACTIVE, null, Objects.requireNonNull(by, "by"), now);
    }

    /** The platform blocks an active seller, for example for fraud or policy violations. */
    public void suspend(String reason, UUID by, Instant now) {
        requireStatus(OrganizationStatus.ACTIVE);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A suspension needs a reason");
        }
        changeStatus(OrganizationStatus.SUSPENDED, reason.strip(), Objects.requireNonNull(by, "by"), now);
    }

    public void reactivate(UUID by, Instant now) {
        requireStatus(OrganizationStatus.SUSPENDED);
        changeStatus(OrganizationStatus.ACTIVE, null, Objects.requireNonNull(by, "by"), now);
    }

    /** An owner or the platform closes the organisation for good. */
    public void close(UUID by, Instant now) {
        requireNotClosed();
        changeStatus(OrganizationStatus.CLOSED, null, Objects.requireNonNull(by, "by"), now);
    }

    public boolean isActive() {
        return status == OrganizationStatus.ACTIVE;
    }

    public boolean isClosed() {
        return status == OrganizationStatus.CLOSED;
    }

    private void requireNotClosed() {
        if (status == OrganizationStatus.CLOSED) {
            throw new IllegalStateException("Organisation is closed");
        }
    }

    private void requireStatus(OrganizationStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Status " + status + " does not allow this change");
        }
    }

    private void changeStatus(OrganizationStatus newStatus, String reason, UUID by, Instant now) {
        this.status = newStatus;
        this.statusReason = reason;
        this.statusChangedBy = by;
        this.statusChangedAt = Objects.requireNonNull(now, "now");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An organisation needs a name");
        }
        return name.strip();
    }

    /** The reason may contain personal details, and toString ends up in logs. */
    @Override
    public String toString() {
        return "Organization[id=" + getId() + ", slug=" + slug + ", status=" + status + "]";
    }
}
