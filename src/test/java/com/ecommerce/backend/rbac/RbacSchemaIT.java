package com.ecommerce.backend.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V3 constraints, seed data, and the backfill of existing users. */
class RbacSchemaIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    // ------------------------------------------------------------------ seed data

    @Test
    void seedsPermissionsAndSystemRoles() {
        // V3 seeded the role:* permissions; later migrations add their own (V4: user:*).
        List<String> permissions = jdbc.queryForList(
                "SELECT code FROM permissions WHERE code LIKE 'role:%' ORDER BY code", String.class);
        List<String> systemRoles = jdbc.queryForList(
                "SELECT code FROM roles WHERE system_role ORDER BY code", String.class);

        assertThat(permissions).containsExactly("role:assign", "role:read", "role:write");
        assertThat(systemRoles).containsExactly("ADMIN", "CUSTOMER");
    }

    @Test
    void adminHoldsEveryPermissionAndCustomerNone() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM permissions", Integer.class);

        assertThat(permissionCount("ADMIN")).isEqualTo(total);
        assertThat(permissionCount("CUSTOMER")).isZero();
    }

    // ------------------------------------------------------------------ constraints

    // One violation per test: after an error PostgreSQL aborts the transaction, and every later
    // statement fails with "current transaction is aborted" instead of reaching its constraint.

    @Test
    @Transactional
    void roleCodesAreUnique() {
        assertThatThrownBy(() -> insertRole("ADMIN"))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uk_roles_code");
    }

    @Test
    @Transactional
    void roleCodesAreUpperSnakeCase() {
        assertThatThrownBy(() -> insertRole("order-manager"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_roles_code_format");
    }

    @Test
    @Transactional
    void permissionCodesFollowResourceColonAction() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO permissions (code, description) VALUES ('ROLE_READ', 'x')"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_permissions_code_format");
    }

    @Test
    @Transactional
    void roleCannotGrantAnUnknownPermission() {
        UUID roleId = insertRole("ORDER_MANAGER");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO role_permissions (role_id, permission_code) VALUES (?, 'order:refund')", roleId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_role_permissions_permission");
    }

    @Test
    @Transactional
    void grantedPermissionCannotBeDeleted() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM permissions WHERE code = 'role:read'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_role_permissions_permission");
    }

    @Test
    @Transactional
    void roleHeldByUsersCannotBeDeleted() {
        UUID userId = insertUser();
        UUID roleId = insertRole("SUPPORT_AGENT");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", userId, roleId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM roles WHERE id = ?", roleId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_user_roles_role");
    }

    @Test
    @Transactional
    void deletingARoleRemovesItsPermissionGrants() {
        UUID roleId = insertRole("TEMP_ROLE");
        jdbc.update("INSERT INTO role_permissions (role_id, permission_code) VALUES (?, 'role:read')", roleId);

        jdbc.update("DELETE FROM roles WHERE id = ?", roleId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE role_id = ?", Integer.class, roleId))
                .isZero();
    }

    @Test
    @Transactional
    void deletingAUserRemovesTheirRolesAndClearsAssignedBy() {
        UUID admin = insertUser();
        UUID customer = insertUser();
        UUID customerRole = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'CUSTOMER'", UUID.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id, assigned_by) VALUES (?, ?, ?)", customer, customerRole, admin);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", admin, customerRole);

        jdbc.update("DELETE FROM users WHERE id = ?", admin);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id = ?", Integer.class, admin)).isZero();
        assertThat(jdbc.queryForObject("SELECT assigned_by FROM user_roles WHERE user_id = ?", UUID.class, customer)).isNull();
    }

    // ------------------------------------------------------------------ data migration

    /**
     * Replays the real upgrade path in a separate, empty schema: migrate to V2, create a user the
     * way Phase 1 did, then apply V3 and check the user received the CUSTOMER role.
     */
    @Test
    void upgradeFromV2GivesExistingUsersTheCustomerRole() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).target("2").load().migrate();
            UUID existingUser = UUID.randomUUID();
            jdbc.update("INSERT INTO " + schema + ".users (id, email, password_hash, status, password_changed_at) "
                    + "VALUES (?, 'old@upgrade.test', '{argon2}x', 'PENDING_VERIFICATION', now())", existingUser);

            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();

            String role = jdbc.queryForObject("SELECT r.code FROM " + schema + ".user_roles ur JOIN " + schema
                    + ".roles r ON r.id = ur.role_id WHERE ur.user_id = ?", String.class, existingUser);
            assertThat(role).isEqualTo("CUSTOMER");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    // ------------------------------------------------------------------ helpers

    private int permissionCount(String roleCode) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id WHERE r.code = ?""",
                Integer.class, roleCode);
        return n == null ? 0 : n;
    }

    private UUID insertRole(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO roles (id, code, name) VALUES (?, ?, ?)", id, code, code);
        return id;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, status, password_changed_at)
                VALUES (?, ?, '{argon2}x', 'PENDING_VERIFICATION', now())""", id, "u-" + id + "@rbac.test");
        return id;
    }
}
