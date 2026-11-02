package com.ecommerce.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that every hot auth and rbac query CAN use the index designed for it.
 *
 * <p>Test tables hold only a few rows, and for tiny tables PostgreSQL correctly prefers a
 * sequential scan. {@code SET LOCAL enable_seqscan = off} (scoped to this transaction only)
 * makes the planner reveal whether an index path exists at all. A missing index then shows up
 * as a "Seq Scan" with a huge cost, and the test fails.
 *
 * <p>The SQL mirrors what Hibernate generates for each repository method.
 */
class QueryPlanIT extends AbstractIntegrationTest {

    private static final String ID = "'01a1868e-9cf4-79d1-b09b-dedd85868415'";
    /** Annotation values must be compile-time constants, so no {@code "a".repeat(64)} here. */
    private static final String HASH = "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "login: user by email           | SELECT * FROM users WHERE email = 'a@b.test'                                         | uk_users_email",
            "me: user summary by id         | SELECT id, email, status FROM users WHERE id = " + ID + "                          | pk_users",
            "refresh: token by hash, locked | SELECT * FROM refresh_tokens WHERE token_hash = " + HASH + " FOR UPDATE             | uk_refresh_tokens_token_hash",
            "logout-all: revoke by user     | UPDATE refresh_tokens SET revoked_at = now(), revoke_reason = 'LOGOUT_ALL' WHERE user_id = " + ID + " AND revoked_at IS NULL | idx_refresh_tokens_user_id",
            "reuse: revoke family           | UPDATE refresh_tokens SET revoked_at = now(), revoke_reason = 'REUSE_DETECTED' WHERE family_id = " + ID + " AND revoked_at IS NULL | idx_refresh_tokens_family_id",
            "verify/reset: token by hash    | SELECT * FROM account_tokens WHERE token_hash = " + HASH + " FOR UPDATE             | uk_account_tokens_token_hash",
            "new link: invalidate older     | UPDATE account_tokens SET consumed_at = now() WHERE user_id = " + ID + " AND purpose = 'PASSWORD_RESET' AND consumed_at IS NULL | idx_account_tokens_user_purpose",
            "failed login: atomic counter   | UPDATE users SET failed_login_count = failed_login_count + 1 WHERE id = " + ID + " | pk_users",
            // Phase 2. The claims join runs on every login and refresh; "no Seq Scan" covers all three tables.
            "token claims: roles + perms    | SELECT r.code, rp.permission_code FROM user_roles ur JOIN roles r ON r.id = ur.role_id LEFT JOIN role_permissions rp ON rp.role_id = r.id WHERE ur.user_id = " + ID + " | pk_user_roles",
            "token claims: role permissions | SELECT r.code, rp.permission_code FROM user_roles ur JOIN roles r ON r.id = ur.role_id LEFT JOIN role_permissions rp ON rp.role_id = r.id WHERE ur.user_id = " + ID + " | pk_role_permissions",
            "last admin: lock ADMIN row     | SELECT * FROM roles WHERE code = 'ADMIN' FOR UPDATE                                 | uk_roles_code",
            "last admin: holders of a role  | SELECT user_id FROM user_roles WHERE role_id = " + ID + "                          | idx_user_roles_role_id",
            "last admin: count active       | SELECT count(*) FROM users WHERE id IN (" + ID + ") AND status = 'ACTIVE'          | idx_users_status_id",
            // Phase 3. One email index serves login, uniqueness and prefix search.
            "admin: email prefix search     | SELECT id FROM users WHERE email LIKE 'ali%'                                         | uk_users_email",
            "admin: filter by status        | SELECT id FROM users WHERE status = 'SUSPENDED'                                      | idx_users_status_id",
            "admin: status page, newest     | SELECT id FROM users WHERE status = 'SUSPENDED' ORDER BY id DESC LIMIT 20             | idx_users_status_id",
            "admin: prefix page, newest     | SELECT id FROM users WHERE email LIKE 'ali%' ORDER BY id DESC LIMIT 20                | uk_users_email",
            "admin: count for a status      | SELECT count(*) FROM users WHERE status = 'SUSPENDED'                                 | idx_users_status_id",
            "me: sessions (live tokens)     | SELECT t.family_id FROM refresh_tokens t JOIN (SELECT family_id, min(issued_at) FROM refresh_tokens WHERE user_id = " + ID + " GROUP BY family_id) f ON f.family_id = t.family_id WHERE t.user_id = " + ID + " AND t.revoked_at IS NULL AND t.expires_at > now() | idx_refresh_tokens_user_id",
            "admin: detail, active sessions | SELECT count(*) FROM refresh_tokens WHERE user_id = " + ID + " AND revoked_at IS NULL AND expires_at > now() | idx_refresh_tokens_user_id",
            "delete role: still assigned?   | SELECT count(*) FROM user_roles WHERE role_id = " + ID + "                         | idx_user_roles_role_id"
    })
    void hotQueryUsesItsIndex(String name, String sql, String expectedIndex) {
        String plan = tx.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            List<String> lines = jdbc.queryForList("EXPLAIN " + sql.strip(), String.class);
            return String.join("\n", lines);
        });

        assertThat(plan).as(name + "\n" + plan).contains(expectedIndex).doesNotContain("Seq Scan");
    }
}
