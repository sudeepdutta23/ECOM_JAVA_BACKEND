package com.ecommerce.backend.org;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Accounts and organisations together (ORGANIZATIONS.md, rules 6 to 8): a seller is never left
 * without an owner by an account going away, and an erased account leaves no organisation data.
 */
class OrganizationDepartureIT extends AbstractIntegrationTest {

    private static final String ORGS = "/api/v1/organizations";
    private static final String DOMAIN = "@org-departure.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private AuthTestClient client;
    private final List<String> slugs = new ArrayList<>();
    private final List<UUID> created = new ArrayList<>();

    private record Account(UUID id, String email, String token) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
    }

    @AfterEach
    void cleanUp() {
        slugs.forEach(slug -> jdbc.update("DELETE FROM organizations WHERE slug = ?", slug));
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id)); // erased rows lost the domain
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private Account account(String... roles) {
        String email = "d-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r "
                    + "WHERE u.email = ? AND r.code = ?", email, role);
        }
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        return new Account(id, email, accessToken(client.login(email, PASSWORD)));
    }

    private String organization(Account founder) {
        String slug = "it-dep-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        ResponseEntity<String> response = client.postJson(ORGS, Map.of("slug", slug, "name", "Shop"), founder.token());
        try {
            return mapper.readTree(response.getBody()).at("/data/id").asText();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> deactivate(Account account) {
        return client.postJson("/api/v1/auth/me/deactivate", Map.of("password", PASSWORD), account.token());
    }

    private ResponseEntity<String> erase(Account account) {
        return client.postJson("/api/v1/auth/me/erase", Map.of("password", PASSWORD), account.token());
    }

    private String status(Account account) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, account.id());
    }

    @Test
    void theOnlyOwnerCannotDeactivateOrEraseButCanOnceTheShopIsCoOwned() {
        Account owner = account();
        Account partner = account();
        String orgId = organization(owner);

        ResponseEntity<String> refused = deactivate(owner);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("ORG_LAST_OWNER").contains(slugs.get(0));
        assertThat(erase(owner).getBody()).contains("ORG_LAST_OWNER");
        assertThat(status(owner)).isEqualTo("ACTIVE");

        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role) VALUES (?::uuid, ?, 'OWNER')",
                orgId, partner.id());
        assertThat(deactivate(owner).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_members WHERE user_id = ?", Long.class,
                owner.id())).as("deactivation is reversible: memberships stay").isEqualTo(1);
    }

    @Test
    void aSuspendedCoOwnerDoesNotCountAsSomeoneWhoCanTakeOver() {
        Account owner = account();
        Account suspendedPartner = account();
        String orgId = organization(owner);
        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role) VALUES (?::uuid, ?, 'OWNER')",
                orgId, suspendedPartner.id());
        jdbc.update("UPDATE users SET status = 'SUSPENDED', status_reason = 'Fraud' WHERE id = ?", suspendedPartner.id());

        assertThat(deactivate(owner).getBody()).contains("ORG_LAST_OWNER").contains("only active owner");
        assertThat(status(owner)).isEqualTo("ACTIVE");
    }

    @Test
    void erasureRemovesEveryMembershipAndEveryInvitationToTheAddressAcrossSellers() {
        Account owner = account();
        Account seller = account();
        String closedShop = organization(owner);
        client.send(HttpMethod.POST, ORGS + "/" + closedShop + "/close", null, owner.token());
        String otherShop = organization(seller);
        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role) VALUES (?::uuid, ?, 'STAFF')",
                otherShop, owner.id());
        // An old, revoked invitation to the address: it still holds the email, so erasure must delete it.
        jdbc.update("""
                INSERT INTO organization_invitations (id, organization_id, email, role, token_hash, created_at, expires_at, revoked_at)
                VALUES (?, ?::uuid, ?, 'MANAGER', encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'hex'),
                        now(), now() + interval '7 days', now())""",
                UUID.randomUUID(), otherShop, owner.email());

        assertThat(erase(owner).getStatusCode()).as("a closed shop needs no owner").isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_members WHERE user_id = ?", Long.class,
                owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_invitations WHERE email = ?", Long.class,
                owner.email())).isZero();
        assertThat(jdbc.queryForObject("SELECT created_by FROM organizations WHERE id = ?::uuid", UUID.class, closedShop))
                .as("the organisation keeps the anonymous ID").isEqualTo(owner.id());
    }

    @Test
    void administratorsCannotEraseASoleOwnerButMaySuspendOne() {
        Account admin = account("ADMIN");
        Account owner = account();
        organization(owner);

        ResponseEntity<String> erase = client.send(HttpMethod.POST, "/api/v1/admin/users/" + owner.id() + "/erase",
                null, admin.token());
        ResponseEntity<String> suspend = client.postJson("/api/v1/admin/users/" + owner.id() + "/suspend",
                Map.of("reason", "Fraud investigation"), admin.token());

        assertThat(erase.getBody()).contains("ORG_LAST_OWNER");
        assertThat(suspend.getStatusCode()).as("suspension is reversible and must not wait").isEqualTo(HttpStatus.OK);
        assertThat(status(owner)).isEqualTo("SUSPENDED");
    }
}
