package com.ecommerce.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the V4 changes at the database level. Rolled back after each test, like {@code AuthSchemaIT}.
 */
@Transactional
class UserManagementSchemaIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID insertUser(String status, String reason, UUID changedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, status, email_verified_at, password_changed_at,
                                   status_reason, status_changed_at, status_changed_by)
                VALUES (?, ?, '{argon2}h', ?, now(), now(), ?, now(), ?)""",
                id, "u-" + id + "@schema.test", status, reason, changedBy);
        return id;
    }

    // ------------------------------------------------------------------ status

    @Test
    void suspendedAccountWithAReasonIsAccepted() {
        UUID admin = insertUser("ACTIVE", null, null);

        UUID id = insertUser("SUSPENDED", "Chargeback fraud", admin);

        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id)).isEqualTo("SUSPENDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void suspensionWithoutAReasonIsRejected(String blank) {
        assertThatThrownBy(() -> insertUser("SUSPENDED", blank, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_suspension_has_reason");
    }

    @Test
    void suspensionWithANullReasonIsRejected() {
        assertThatThrownBy(() -> insertUser("SUSPENDED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_suspension_has_reason");
    }

    @Test
    void unknownStatusIsStillRejected() {
        assertThatThrownBy(() -> insertUser("BANNED", "x", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_status");
    }

    @Test
    void deletingTheAdministratorKeepsTheAccountsTheyChanged() {
        UUID admin = insertUser("ACTIVE", null, null);
        UUID suspended = insertUser("SUSPENDED", "Abuse", admin);

        jdbc.update("DELETE FROM users WHERE id = ?", admin);

        assertThat(jdbc.queryForObject("SELECT status_changed_by FROM users WHERE id = ?", UUID.class, suspended)).isNull();
    }

    @Test
    void statusChangedByMustBeARealUser() {
        assertThatThrownBy(() -> insertUser("ACTIVE", null, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_users_status_changed_by");
    }

    // ------------------------------------------------------------------ refresh tokens

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNT_SUSPENDED", "ADMIN_SIGN_OUT"})
    void newRevokeReasonsAreAccepted(String reason) {
        UUID user = insertUser("ACTIVE", null, null);
        UUID token = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, issued_at, expires_at, revoked_at, revoke_reason)
                VALUES (?, ?, ?, ?, now(), now() + interval '1 day', now(), ?)""",
                token, user, UUID.randomUUID(), "c".repeat(64), reason);

        assertThat(jdbc.queryForObject("SELECT revoke_reason FROM refresh_tokens WHERE id = ?", String.class, token))
                .isEqualTo(reason);
    }

    // ------------------------------------------------------------------ permissions and indexes

    @Test
    void adminHoldsTheNewPermissions() {
        List<String> adminPermissions = jdbc.queryForList("""
                SELECT rp.permission_code FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                WHERE r.code = 'ADMIN' AND rp.permission_code LIKE 'user:%' ORDER BY 1""", String.class);

        assertThat(adminPermissions).contains("user:manage", "user:read"); // later migrations add more (V5: user:erase)
    }

    @Test
    void theEmailUniqueIndexAlsoServesPrefixSearch() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_users_email'", String.class);

        assertThat(definition).startsWith("CREATE UNIQUE INDEX").contains("varchar_pattern_ops");
    }

    @Test
    void listAndSearchIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'users' AND indexname LIKE 'idx_users_%' ORDER BY 1",
                String.class);

        assertThat(indexes).containsExactly("idx_users_status_changed_by", "idx_users_status_id");
    }
}
