package com.ecommerce.backend.rbac.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import com.ecommerce.backend.rbac.Permission;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A named bundle of permissions.
 *
 * <p><b>Why {@code @ElementCollection} instead of a {@code Permission} entity association?</b>
 * A role's permissions are plain values with no life of their own here: the set is replaced as a
 * whole when an admin edits the role. An element collection maps exactly that onto
 * {@code role_permissions}, with no join entity to manage. The catalogue table still guards the
 * codes through its foreign key.
 */
@Entity
@Table(name = "roles")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role extends BaseEntity {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String ADMIN = "ADMIN";

    @Column(name = "code", nullable = false, updatable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "system_role", nullable = false, updatable = false)
    private boolean systemRole;

    /** Two admins editing the same role at once: the second save fails instead of overwriting. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_code", nullable = false, length = 64)
    @Convert(converter = PermissionConverter.class)
    private Set<Permission> permissions = new HashSet<>();

    public static Role custom(String code, String name, String description, Set<Permission> permissions) {
        Role role = new Role();
        role.code = Objects.requireNonNull(code, "code");
        role.name = Objects.requireNonNull(name, "name");
        role.description = description;
        role.systemRole = false;
        role.permissions = new HashSet<>(permissions);
        return role;
    }

    public void update(String name, String description, Set<Permission> permissions) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        // Mutate the managed collection in place, so Hibernate can diff it.
        this.permissions.clear();
        this.permissions.addAll(permissions);
    }

    /** Read-only view; changes go through {@link #update}. */
    public Set<Permission> getPermissions() {
        return permissions.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(Permission.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }

    @Override
    public String toString() {
        return "Role[code=" + code + "]";
    }
}
