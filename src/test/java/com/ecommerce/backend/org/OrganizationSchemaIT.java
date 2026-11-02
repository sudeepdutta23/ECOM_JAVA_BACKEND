package com.ecommerce.backend.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V7 constraints, cascades and seed permissions, straight against the database. */
class OrganizationSchemaIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@org-schema.test";

    @Autowired
    private JdbcTemplate jdbc;

    private UUID founder;

    @BeforeEach
    void setUp() {
        founder = user();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM organizations WHERE slug LIKE 'it-schema-%'");
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, status, password_changed_at)
                VALUES (?, ?, '{argon2}x', 'PENDING_VERIFICATION', now())""", id, "u-" + id + DOMAIN);
        return id;
    }

    private UUID organization(String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, slug, name, status, created_by) VALUES (?, ?, 'Shop', 'PENDING_APPROVAL', ?)",
                id, slug, founder);
        return id;
    }

    private void member(UUID organizationId, UUID userId, String role) {
        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role) VALUES (?, ?, ?)",
                organizationId, userId, role);
    }

    private static String slug() {
        return "it-schema-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ organizations

    @Test
    void slugsAreUniqueAndMustBeLowerCaseHandles() {
        String slug = slug();
        organization(slug);

        assertThatThrownBy(() -> organization(slug)).isInstanceOf(DuplicateKeyException.class);
        for (String invalid : List.of("it-schema-UPPER", "it-schema-", "-it-schema", "it schema", "ab")) {
            assertThatThrownBy(() -> organization(invalid)).as(invalid)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_organizations_slug_format");
        }
    }

    @Test
    void namesMustBeTrimmedAndNotEmpty() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO organizations (id, slug, name, status) VALUES (?, ?, ' Shop', 'ACTIVE')", UUID.randomUUID(), slug()))
                .hasMessageContaining("ck_organizations_name_trimmed");
    }

    @Test
    void aSuspensionNeedsAReasonAndUnknownStatusesAreRejected() {
        UUID id = organization(slug());

        assertThatThrownBy(() -> jdbc.update("UPDATE organizations SET status = 'SUSPENDED' WHERE id = ?", id))
                .hasMessageContaining("ck_organizations_suspension_has_reason");
        assertThat(jdbc.update("UPDATE organizations SET status = 'SUSPENDED', status_reason = 'Fraud' WHERE id = ?", id))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE organizations SET status = 'DELETED' WHERE id = ?", id))
                .hasMessageContaining("ck_organizations_status");
    }

    @Test
    void deletingTheFounderKeepsTheOrganization() {
        UUID id = organization(slug());
        jdbc.update("UPDATE organizations SET status_changed_by = ? WHERE id = ?", founder, id);

        jdbc.update("DELETE FROM users WHERE id = ?", founder);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM organizations WHERE id = ? AND created_by IS NULL "
                + "AND status_changed_by IS NULL", Long.class, id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ members

    @Test
    void aUserIsAMemberOnceWithOneKnownRole() {
        UUID id = organization(slug());
        member(id, founder, "OWNER");

        assertThatThrownBy(() -> member(id, founder, "STAFF")).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> member(id, user(), "ADMIN"))
                .hasMessageContaining("ck_organization_members_role");
    }

    @Test
    void membershipsDisappearWithTheirOrganizationOrTheirUser() {
        UUID shop = organization(slug());
        UUID other = organization(slug());
        UUID staff = user();
        member(shop, founder, "OWNER");
        member(shop, staff, "STAFF");
        member(other, staff, "MANAGER");

        jdbc.update("DELETE FROM organizations WHERE id = ?", shop);
        jdbc.update("DELETE FROM users WHERE id = ?", staff);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_members WHERE organization_id IN (?, ?)",
                Long.class, shop, other)).isZero();
    }

    @Test
    void deletingWhoAddedAMemberKeepsTheMembership() {
        UUID shop = organization(slug());
        UUID manager = user();
        UUID staff = user();
        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role, added_by) VALUES (?, ?, 'STAFF', ?)",
                shop, staff, manager);

        jdbc.update("DELETE FROM users WHERE id = ?", manager);

        assertThat(jdbc.queryForObject("SELECT added_by IS NULL FROM organization_members WHERE user_id = ?",
                Boolean.class, staff)).isTrue();
    }

    // ------------------------------------------------------------------ invitations (V8)

    private void invitation(UUID organizationId, String email, String hash) {
        jdbc.update("""
                INSERT INTO organization_invitations (id, organization_id, email, role, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, 'STAFF', ?, now(), now() + interval '7 days')""",
                UUID.randomUUID(), organizationId, email, hash);
    }

    @Test
    void onlyOneOpenInvitationPerAddressButClosedOnesMayPileUp() {
        UUID id = organization(slug());
        invitation(id, "bob@example.com", "a".repeat(64));

        assertThatThrownBy(() -> invitation(id, "bob@example.com", "b".repeat(64)))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_organization_invitations_open");
        jdbc.update("UPDATE organization_invitations SET revoked_at = now() WHERE organization_id = ?", id);
        invitation(id, "bob@example.com", "b".repeat(64));
    }

    @Test
    void anInvitationEndsOneWayAndStoresOnlyNormalizedEmailsAndHexHashes() {
        UUID id = organization(slug());
        invitation(id, "bob@example.com", "c".repeat(64));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE organization_invitations SET accepted_at = now(), revoked_at = now() WHERE organization_id = ?", id))
                .hasMessageContaining("ck_organization_invitations_single_outcome");
        assertThatThrownBy(() -> invitation(id, "Bob@Example.com", "d".repeat(64)))
                .hasMessageContaining("ck_organization_invitations_email_normalized");
        assertThatThrownBy(() -> invitation(id, "eve@example.com", "raw-token-not-a-hash"))
                .hasMessageContaining("ck_organization_invitations_hash_format");
    }

    @Test
    void invitationsDisappearWithTheirOrganization() {
        UUID id = organization(slug());
        invitation(id, "bob@example.com", "e".repeat(64));

        jdbc.update("DELETE FROM organizations WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_invitations WHERE organization_id = ?",
                Long.class, id)).isZero();
    }

    // ------------------------------------------------------------------ permissions and indexes

    @Test
    void platformAdministratorsHoldTheOrganizationPermissions() {
        List<String> adminPermissions = jdbc.queryForList("""
                SELECT rp.permission_code FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                WHERE r.code = 'ADMIN' AND rp.permission_code LIKE 'org:%' ORDER BY 1""", String.class);

        assertThat(adminPermissions).containsExactly("org:manage", "org:read");
    }

    @Test
    void indexesForQueuesMembershipsAndForeignKeysExist() {
        List<String> indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE tablename IN ('organizations', 'organization_members')""",
                String.class);

        assertThat(indexes).contains("pk_organizations", "uk_organizations_slug", "idx_organizations_status_id",
                "idx_organizations_status_changed_by", "idx_organizations_created_by", "pk_organization_members",
                "idx_organization_members_user_id", "idx_organization_members_added_by");
    }
}
