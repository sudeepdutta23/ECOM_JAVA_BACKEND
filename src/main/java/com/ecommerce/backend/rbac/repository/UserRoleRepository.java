package com.ecommerce.backend.rbac.repository;

import com.ecommerce.backend.rbac.entity.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRole.Key> {

    /** One row per (role, permission) pair; a role without permissions yields one row with a null permission. */
    interface RolePermissionRow {
        String getRoleCode();

        String getPermissionCode();
    }

    /**
     * Everything needed to build a user's token claims, in ONE query.
     *
     * <p>Runs on every login and refresh. Loading {@code UserRole} entities, then each {@code Role},
     * then each role's permission collection would be the classic N+1 pattern: 1 + roles + roles
     * queries. The plan uses {@code pk_user_roles} (leading column {@code user_id}),
     * {@code pk_roles} and {@code pk_role_permissions}.
     *
     * <p>Aliases are double-quoted: PostgreSQL lower-cases unquoted identifiers, and the projection
     * matches {@code getRoleCode()} to the alias {@code roleCode}.
     */
    @Query(value = """
            SELECT r.code AS "roleCode", rp.permission_code AS "permissionCode"
            FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
            LEFT JOIN role_permissions rp ON rp.role_id = r.id
            WHERE ur.user_id = :userId
            """, nativeQuery = true)
    List<RolePermissionRow> findRolesAndPermissions(@Param("userId") UUID userId);

    /** A user's roles with assignment details, for the admin view. One join, no N+1. */
    @Query("""
            select new com.ecommerce.backend.rbac.repository.UserRoleView(
                r.id, r.code, r.name, r.systemRole, ur.assignedAt, ur.assignedBy)
            from UserRole ur join Role r on r.id = ur.roleId
            where ur.userId = :userId
            order by r.code
            """)
    List<UserRoleView> findAssignments(@Param("userId") UUID userId);

    /** Served by {@code idx_user_roles_role_id}. */
    long countByRoleId(UUID roleId);

    /** Served by {@code idx_user_roles_role_id}. Used for the last-admin rule. */
    @Query("select ur.userId from UserRole ur where ur.roleId = :roleId")
    List<UUID> findUserIdsByRoleId(@Param("roleId") UUID roleId);

    /**
     * Grants a role unless the user already holds it.
     *
     * <p>"Check, then insert" is not idempotent under concurrency: two identical requests both see
     * "not held" and the second INSERT fails on the primary key. {@code ON CONFLICT DO NOTHING}
     * makes the database decide atomically.
     *
     * @return 1 if the role was granted now, 0 if it was already held
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO user_roles (user_id, role_id, assigned_at, assigned_by)
            VALUES (:userId, :roleId, :now, :assignedBy)
            ON CONFLICT (user_id, role_id) DO NOTHING
            """, nativeQuery = true)
    int grantIfAbsent(@Param("userId") UUID userId, @Param("roleId") UUID roleId,
                      @Param("now") Instant now, @Param("assignedBy") UUID assignedBy);

    /** @return 1 if the role was revoked now, 0 if it was not held */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.userId = :userId and ur.roleId = :roleId")
    int revoke(@Param("userId") UUID userId, @Param("roleId") UUID roleId);

    /** Every role of a user, CUSTOMER included; only for erasure. Served by {@code pk_user_roles}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.userId = :userId")
    int revokeAll(@Param("userId") UUID userId);
}
