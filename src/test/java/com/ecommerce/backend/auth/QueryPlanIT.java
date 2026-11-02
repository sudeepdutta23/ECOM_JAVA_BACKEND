package com.ecommerce.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that every hot auth, rbac, user-admin and org query uses the index designed for it.
 *
 * <p>Test tables hold only a few rows, and for tiny tables PostgreSQL correctly prefers a
 * sequential scan. {@code SET LOCAL enable_seqscan = off} (scoped to this transaction only)
 * makes the planner reveal whether an index path exists at all. A missing index then shows up
 * as a "Seq Scan" with a huge cost, and the test fails.
 *
 * <p>The SQL mirrors what Hibernate generates for each repository method.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // non-static @BeforeAll with injected beans
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
            "delete role: still assigned?   | SELECT count(*) FROM user_roles WHERE role_id = " + ID + "                         | idx_user_roles_role_id",
            // Step 3.5: erasure deletes everything else that belongs to the account.
            "erase: delete sessions         | DELETE FROM refresh_tokens WHERE user_id = " + ID + "                              | idx_refresh_tokens_user_id",
            "erase: delete email links      | DELETE FROM account_tokens WHERE user_id = " + ID + "                              | idx_account_tokens_user_purpose",
            "erase: delete role assignments | DELETE FROM user_roles WHERE user_id = " + ID + "                                  | pk_user_roles",
            // Phase 4: organisations. Membership checks run on every seller request (step 4.4).
            "org: storefront by slug        | SELECT * FROM organizations WHERE slug = 'acme-store'                                | uk_organizations_slug",
            "org: approval queue, newest    | SELECT id FROM organizations WHERE status = 'PENDING_APPROVAL' ORDER BY id DESC LIMIT 20 | idx_organizations_status_id",
            "org: my organisations          | SELECT organization_id, role FROM organization_members WHERE user_id = " + ID + "     | idx_organization_members_user_id",
            "org: guard (every seller call) | SELECT m.role, o.status FROM organization_members m JOIN organizations o ON o.id = m.organization_id WHERE m.organization_id = " + ID + " AND m.user_id = " + ID + " | pk_organizations",
            "org: guard, membership side    | SELECT m.role, o.status FROM organization_members m JOIN organizations o ON o.id = m.organization_id WHERE m.organization_id = " + ID + " AND m.user_id = " + ID + " | pk_organization_members or idx_organization_members_user_id",
            "org: owner IDs (last owner)    | SELECT user_id FROM organization_members WHERE organization_id = " + ID + " AND role = 'OWNER' | pk_organization_members",
            "org: owners left (last owner)  | SELECT count(*) FROM organization_members WHERE organization_id = " + ID + " AND role = 'OWNER' | pk_organization_members",
            "org: owned limit (create)      | SELECT count(*) FROM organization_members m JOIN organizations o ON o.id = m.organization_id WHERE m.user_id = " + ID + " AND m.role = 'OWNER' AND o.status <> 'CLOSED' | idx_organization_members_user_id",
            "org: my organisations, page    | SELECT o.id, o.slug, m.role FROM organization_members m JOIN organizations o ON o.id = m.organization_id WHERE m.user_id = " + ID + " ORDER BY o.id DESC LIMIT 20 | idx_organization_members_user_id",
            "org: accept, invitation locked | SELECT * FROM organization_invitations WHERE token_hash = " + HASH + " FOR UPDATE    | uk_organization_invitations_token_hash",
            "org: open invitation for email | SELECT id FROM organization_invitations WHERE organization_id = " + ID + " AND email = 'bob@example.com' AND accepted_at IS NULL AND revoked_at IS NULL | uk_organization_invitations_open or idx_organization_invitations_email",
            "org: pending invitations, page | SELECT id FROM organization_invitations WHERE organization_id = " + ID + " AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now() ORDER BY created_at DESC, id DESC LIMIT 20 | uk_organization_invitations_open",
            "admin: organisations, newest   | SELECT id FROM organizations ORDER BY id DESC LIMIT 20                              | pk_organizations",
            "departure: owned open, locked  | SELECT o.id FROM organizations o WHERE o.status <> 'CLOSED' AND o.id IN (SELECT m.organization_id FROM organization_members m WHERE m.user_id = " + ID + " AND m.role = 'OWNER') ORDER BY o.id FOR UPDATE OF o | idx_organization_members_user_id",
            "erase: delete memberships      | DELETE FROM organization_members WHERE user_id = " + ID + "                         | idx_organization_members_user_id",
            "erase: delete invitations      | DELETE FROM organization_invitations WHERE email = 'bob@example.com'                | idx_organization_invitations_email",
            "org: member list, page         | SELECT user_id, role FROM organization_members WHERE organization_id = " + ID + " ORDER BY joined_at, user_id LIMIT 20 | pk_organization_members"
    })
    void hotQueryUsesItsIndex(String name, String sql, String expectedIndex) {
        String plan = tx.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            List<String> lines = jdbc.queryForList("EXPLAIN " + sql.strip(), String.class);
            return String.join("\n", lines);
        });

        // "a or b": two indexes are equally selective here, and either one is a good plan.
        assertThat(plan).as(name + "\n" + plan).doesNotContain("Seq Scan");
        assertThat(expectedIndex.split(" or ")).as(name + "\n" + plan).anyMatch(plan::contains);
    }

    /**
     * With only the handful of rows other tests leave behind, several indexes cost the same, and
     * which one the planner picks depends on leftover index pages: the test would pass or fail by
     * test order. A few thousand rows and fresh statistics make the costs reflect real selectivity.
     * Loaded once for the class, then removed again, with statistics refreshed both times.
     */
    @BeforeAll
    void loadRealisticVolume() {
        jdbc.execute("""
                INSERT INTO users (id, email, password_hash, status, status_reason, email_verified_at, password_changed_at)
                SELECT gen_random_uuid(), 'plan-' || g || '@plan.test', '{argon2}x',
                       CASE WHEN g % 50 = 0 THEN 'SUSPENDED' ELSE 'ACTIVE' END,
                       CASE WHEN g % 50 = 0 THEN 'plan' END, now(), now()
                FROM generate_series(1, 5000) g""");
        jdbc.execute("""
                INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, issued_at, expires_at)
                SELECT gen_random_uuid(), u.id, gen_random_uuid(), encode(sha256(convert_to(u.id::text, 'UTF8')), 'hex'),
                       now(), now() + interval '7 days'
                FROM users u WHERE u.email LIKE 'plan-%'""");
        jdbc.execute("""
                INSERT INTO account_tokens (id, user_id, purpose, token_hash, expires_at)
                SELECT gen_random_uuid(), u.id, 'EMAIL_VERIFICATION',
                       encode(sha256(convert_to('a' || u.id::text, 'UTF8')), 'hex'), now() + interval '1 day'
                FROM users u WHERE u.email LIKE 'plan-%'""");
        jdbc.execute("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.id, r.id FROM users u, roles r WHERE u.email LIKE 'plan-%' AND r.code = 'CUSTOMER'""");
        // One seller per 2.5 accounts, each with its founder as owner and one staff member.
        jdbc.execute("""
                INSERT INTO organizations (id, slug, name, status, created_by)
                SELECT gen_random_uuid(), 'plan-' || substr(md5(u.id::text), 1, 20), 'Plan shop',
                       CASE WHEN row_number() OVER () % 20 = 0 THEN 'PENDING_APPROVAL' ELSE 'ACTIVE' END, u.id
                FROM users u WHERE u.email LIKE 'plan-%' AND random() < 0.4""");
        jdbc.execute("""
                INSERT INTO organization_members (organization_id, user_id, role)
                SELECT o.id, o.created_by, 'OWNER' FROM organizations o WHERE o.slug LIKE 'plan-%'""");
        jdbc.execute("""
                INSERT INTO organization_members (organization_id, user_id, role)
                SELECT o.id, u.id, 'STAFF' FROM organizations o
                JOIN users u ON u.email = 'plan-' || (1 + abs(hashtext(o.id::text)) % 5000) || '@plan.test'
                WHERE o.slug LIKE 'plan-%' AND u.id <> o.created_by""");
        jdbc.execute("""
                INSERT INTO organization_invitations (id, organization_id, email, role, token_hash, created_at, expires_at)
                SELECT gen_random_uuid(), o.id, 'inv-' || o.id || '@plan.test', 'STAFF',
                       encode(sha256(convert_to('i' || o.id::text, 'UTF8')), 'hex'), now(), now() + interval '7 days'
                FROM organizations o WHERE o.slug LIKE 'plan-%'""");
        jdbc.execute("ANALYZE users, refresh_tokens, account_tokens, user_roles, organizations, organization_members, "
                + "organization_invitations");
    }

    @AfterAll
    void removeVolume() {
        jdbc.update("DELETE FROM organizations WHERE slug LIKE 'plan-%'"); // cascades to members and invitations
        jdbc.update("DELETE FROM users WHERE email LIKE 'plan-%@plan.test'"); // cascades to tokens and roles
        jdbc.execute("ANALYZE users, refresh_tokens, account_tokens, user_roles, organizations, organization_members, "
                + "organization_invitations");
    }
}
