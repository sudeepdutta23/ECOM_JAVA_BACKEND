package com.ecommerce.backend.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A user holding a role.
 *
 * <p><b>Module boundary:</b> the user is referenced by ID only, not by a JPA association to
 * {@code auth.entity.User}. The rbac module must not depend on the auth module's entities;
 * the database foreign key still guarantees integrity.
 *
 * <p><b>Why {@link Persistable}?</b> The composite key is assigned by us, never null, so
 * Spring Data cannot tell a new row from an existing one and would run a SELECT before every
 * INSERT ({@code merge}). The transient {@code isNew} flag tells it directly.
 */
@Entity
@Table(name = "user_roles")
@IdClass(UserRole.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRole implements Persistable<UserRole.Key> {

    /** Composite primary key (user_id, role_id). */
    public record Key(UUID userId, UUID roleId) implements Serializable {
    }

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    /** Null when the system granted it (registration, bootstrap, migration). */
    @Column(name = "assigned_by", updatable = false)
    private UUID assignedBy;

    @Transient
    private boolean isNew;

    public static UserRole grant(UUID userId, UUID roleId, Instant now, UUID assignedBy) {
        UserRole grant = new UserRole();
        grant.userId = Objects.requireNonNull(userId, "userId");
        grant.roleId = Objects.requireNonNull(roleId, "roleId");
        grant.assignedAt = Objects.requireNonNull(now, "now");
        grant.assignedBy = assignedBy;
        grant.isNew = true;
        return grant;
    }

    @Override
    public Key getId() {
        return new Key(userId, roleId);
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
