package com.ecommerce.backend.org.entity;

import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.OrgRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * A user working for an organisation, with exactly one {@link OrgRole} there.
 *
 * <p>Both sides are referenced by ID, like {@code rbac.entity.UserRole}: the composite key is
 * assigned by us, so {@link Persistable} tells Spring Data the row is new instead of letting it
 * SELECT before every INSERT.
 */
@Entity
@Table(name = "organization_members")
@IdClass(OrganizationMember.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationMember implements Persistable<OrganizationMember.Key> {

    /** Composite primary key (organization_id, user_id). */
    public record Key(UUID organizationId, UUID userId) implements Serializable {
    }

    @Id
    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private OrgRole role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    /** Null for the founding owner. */
    @Column(name = "added_by", updatable = false)
    private UUID addedBy;

    @Transient
    private boolean isNew;

    /** The user who created the organisation becomes its first owner. */
    public static OrganizationMember founder(UUID organizationId, UUID userId, Instant now) {
        return join(organizationId, userId, OrgRole.OWNER, now, null);
    }

    /** A user joins, for example by accepting an invitation (step 4.3). */
    public static OrganizationMember join(UUID organizationId, UUID userId, OrgRole role, Instant now, UUID addedBy) {
        OrganizationMember member = new OrganizationMember();
        member.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        member.userId = Objects.requireNonNull(userId, "userId");
        member.role = Objects.requireNonNull(role, "role");
        member.joinedAt = Objects.requireNonNull(now, "now");
        member.addedBy = addedBy;
        member.isNew = true;
        return member;
    }

    public void changeRole(OrgRole newRole) {
        this.role = Objects.requireNonNull(newRole, "role");
    }

    public boolean can(OrgPermission permission) {
        return role.grants(permission);
    }

    @Override
    public Key getId() {
        return new Key(organizationId, userId);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
