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
 * Proves that every hot auth, rbac, user-admin, org, profile, catalogue, order and notification query uses the index designed for it.
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
            "org: member list, page         | SELECT user_id, role FROM organization_members WHERE organization_id = " + ID + " ORDER BY joined_at, user_id LIMIT 20 | pk_organization_members",
            // Phase 5: profile and address book. Every write locks the account row first.
            "profile: save, lock account    | SELECT status FROM users WHERE id = " + ID + " FOR SHARE                           | pk_users",
            "profile: by account            | SELECT * FROM user_profiles WHERE user_id = " + ID + "                             | pk_user_profiles",
            "address: my addresses, newest  | SELECT * FROM user_addresses WHERE user_id = " + ID + " ORDER BY id DESC           | idx_user_addresses_user_id",
            "address: count for the limit   | SELECT count(*) FROM user_addresses WHERE user_id = " + ID + "                     | idx_user_addresses_user_id",
            "address: one, scoped to owner  | SELECT * FROM user_addresses WHERE id = " + ID + " AND user_id = " + ID + "         | pk_user_addresses or idx_user_addresses_user_id",
            "address: clear default         | UPDATE user_addresses SET default_shipping = false, version = version + 1 WHERE user_id = " + ID + " AND default_shipping AND id <> " + ID + " | uk_user_addresses_default_shipping or idx_user_addresses_user_id",
            "erase: delete addresses        | DELETE FROM user_addresses WHERE user_id = " + ID + "                              | idx_user_addresses_user_id",
            // Phase 6: catalogue. The storefront pages are the hottest public queries.
            "catalog: storefront, newest    | SELECT id FROM products WHERE status = 'ACTIVE' AND seller_active ORDER BY id DESC LIMIT 20 | idx_products_storefront",
            "catalog: storefront, category  | SELECT id FROM products WHERE category_id = " + ID + " AND status = 'ACTIVE' AND seller_active ORDER BY id DESC LIMIT 20 | idx_products_category_id",
            "catalog: storefront, seller    | SELECT id FROM products WHERE organization_id = " + ID + " AND status = 'ACTIVE' AND seller_active ORDER BY id DESC LIMIT 20 | idx_products_organization_id",
            "catalog: seller's list, newest | SELECT id FROM products WHERE organization_id = " + ID + " ORDER BY id DESC LIMIT 20 | idx_products_organization_id",
            "catalog: product of its seller | SELECT * FROM products WHERE id = " + ID + " AND organization_id = " + ID + " | pk_products or idx_products_organization_id",
            "catalog: SKU taken?            | SELECT 1 FROM products WHERE organization_id = " + ID + " AND sku = 'MUG-1'        | uk_products_organization_sku",
            "catalog: atomic stock change   | UPDATE products SET stock = stock - 1 WHERE id = " + ID + " AND organization_id = " + ID + " AND stock - 1 >= 0 | pk_products or idx_products_organization_id",
            "catalog: seller status change  | UPDATE products SET seller_active = false WHERE organization_id = " + ID + " AND seller_active <> false | idx_products_organization_id",
            "catalog: category in use?      | SELECT 1 FROM products WHERE category_id = " + ID + " LIMIT 1                     | idx_products_category_id",
            "catalog: category by slug      | SELECT * FROM categories WHERE slug = 'books'                                      | uk_categories_slug",
            "org: seller write, lock status | SELECT status FROM organizations WHERE id = " + ID + " FOR SHARE                   | pk_organizations",
            // Phase 6: cart, checkout, orders.
            "cart: my lines                 | SELECT * FROM cart_items WHERE user_id = " + ID + " ORDER BY added_at, product_id | pk_cart_items",
            "cart: upsert a line            | SELECT 1 FROM cart_items WHERE user_id = " + ID + " AND product_id = " + ID + " | pk_cart_items or idx_cart_items_product_id",
            "checkout: reserve stock        | UPDATE products SET stock = stock - 1 WHERE id = " + ID + " AND status = 'ACTIVE' AND seller_active AND stock >= 1 | pk_products or idx_products_storefront",
            "checkout: idempotency key      | SELECT id FROM checkouts WHERE customer_id = " + ID + " AND idempotency_key = 'k-12345678' | uk_checkouts_customer_key",
            "orders: my orders, newest      | SELECT id FROM orders WHERE customer_id = " + ID + " ORDER BY id DESC LIMIT 20     | idx_orders_customer_id",
            "orders: seller's, by status    | SELECT id FROM orders WHERE organization_id = " + ID + " AND status = 'PAID' ORDER BY id DESC LIMIT 20 | idx_orders_organization_status or idx_orders_organization_id",
            "orders: seller's, newest       | SELECT id FROM orders WHERE organization_id = " + ID + " ORDER BY id DESC LIMIT 20 | idx_orders_organization_id",
            "orders: of a checkout          | SELECT id FROM orders WHERE checkout_id = " + ID + " ORDER BY id                   | idx_orders_checkout_id",
            "orders: lines of an order      | SELECT * FROM order_items WHERE order_id = " + ID + " ORDER BY line_no            | pk_order_items",
            // Phase 7: the inbox and its badge, polled by every signed-in page.
            "notifications: inbox, newest   | SELECT id FROM notifications WHERE user_id = " + ID + " ORDER BY id DESC LIMIT 20 | idx_notifications_user_id",
            "notifications: unread badge    | SELECT count(*) FROM notifications WHERE user_id = " + ID + " AND read_at IS NULL | idx_notifications_unread or idx_notifications_user_id"
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
        // A profile and two addresses per account, the first one the default for both.
        jdbc.execute("""
                INSERT INTO user_profiles (user_id, given_name)
                SELECT u.id, 'Plan' FROM users u WHERE u.email LIKE 'plan-%'""");
        jdbc.execute("""
                INSERT INTO user_addresses (id, user_id, recipient_name, line1, city, postal_code, country_code,
                                            default_shipping, default_billing)
                SELECT gen_random_uuid(), u.id, 'Plan', 'Street ' || g, 'Berlin', '10117', 'DE', g = 1, g = 1
                FROM users u CROSS JOIN generate_series(1, 2) g WHERE u.email LIKE 'plan-%'""");
        // Twenty categories, and three products per seller, most of them on sale.
        jdbc.execute("""
                INSERT INTO categories (id, slug, name)
                SELECT gen_random_uuid(), 'plan-' || g, 'Plan ' || g FROM generate_series(1, 20) g""");
        jdbc.execute("""
                INSERT INTO products (id, organization_id, category_id, sku, name, price_minor, currency, stock, status, seller_active)
                SELECT gen_random_uuid(), o.id, c.id, 'PLAN-' || g, 'Plan product', 1000, 'EUR', 10,
                       CASE WHEN g = 3 THEN 'DRAFT' ELSE 'ACTIVE' END, o.status = 'ACTIVE'
                FROM organizations o CROSS JOIN generate_series(1, 3) g
                JOIN categories c ON c.slug = 'plan-' || (1 + abs(hashtext(o.id::text || g)) % 20)
                WHERE o.slug LIKE 'plan-%'""");
        // One checkout with one order per account that has a seller, plus a cart line for everyone.
        jdbc.execute("""
                INSERT INTO checkouts (id, customer_id, idempotency_key, total_minor, currency)
                SELECT gen_random_uuid(), u.id, 'plan-' || substr(md5(u.id::text), 1, 20), 1000, 'EUR'
                FROM users u WHERE u.email LIKE 'plan-%'""");
        jdbc.execute("""
                INSERT INTO orders (id, checkout_id, organization_id, seller_name, customer_id, status, currency, total_minor,
                                    shipping_address, billing_address, paid_at)
                SELECT gen_random_uuid(), c.id, o.id, 'Plan shop', c.customer_id,
                       CASE WHEN row_number() OVER () % 3 = 0 THEN 'PAID' ELSE 'PENDING_PAYMENT' END, 'EUR', 1000,
                       '{}'::jsonb, '{}'::jsonb, now()
                FROM checkouts c JOIN organizations o ON o.created_by = c.customer_id
                WHERE c.idempotency_key LIKE 'plan-%'""");
        jdbc.execute("""
                INSERT INTO cart_items (user_id, product_id, quantity)
                SELECT DISTINCT ON (u.id) u.id, p.id, 1 FROM users u JOIN products p ON p.created_by IS NULL
                WHERE u.email LIKE 'plan-%' AND p.sku = 'PLAN-1' AND p.organization_id IN (
                    SELECT id FROM organizations WHERE slug LIKE 'plan-%' LIMIT 50)""");
        jdbc.execute("ANALYZE users, refresh_tokens, account_tokens, user_roles, organizations, organization_members, "
                + "organization_invitations, user_profiles, user_addresses, categories, products, cart_items, checkouts, orders");
    }

    @AfterAll
    void removeVolume() {
        jdbc.update("DELETE FROM orders WHERE checkout_id IN (SELECT id FROM checkouts WHERE idempotency_key LIKE 'plan-%')");
        jdbc.update("DELETE FROM checkouts WHERE idempotency_key LIKE 'plan-%'");
        jdbc.update("DELETE FROM cart_items WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'plan-%@plan.test')");
        jdbc.update("DELETE FROM products WHERE organization_id IN (SELECT id FROM organizations WHERE slug LIKE 'plan-%')");
        jdbc.update("DELETE FROM categories WHERE slug LIKE 'plan-%'");
        jdbc.update("DELETE FROM organizations WHERE slug LIKE 'plan-%'"); // cascades to members and invitations
        jdbc.update("DELETE FROM users WHERE email LIKE 'plan-%@plan.test'"); // cascades to tokens, roles, profiles, addresses
        jdbc.execute("ANALYZE users, refresh_tokens, account_tokens, user_roles, organizations, organization_members, "
                + "organization_invitations, user_profiles, user_addresses, categories, products, cart_items, checkouts, orders");
    }
}
