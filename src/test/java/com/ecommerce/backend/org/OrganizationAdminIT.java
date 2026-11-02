package com.ecommerce.backend.org;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Platform administration of sellers, and owners closing their own, over real HTTP. */
@ExtendWith(OutputCaptureExtension.class)
class OrganizationAdminIT extends AbstractIntegrationTest {

    private static final String ADMIN_ORGS = "/api/v1/admin/organizations";
    private static final String ORGS = "/api/v1/organizations";
    private static final String DOMAIN = "@org-admin.test";

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
    private Account admin;
    private Account owner;

    private record Account(UUID id, String token) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        admin = account("ADMIN");
        owner = account();
    }

    @AfterEach
    void cleanUp() {
        slugs.forEach(slug -> jdbc.update("DELETE FROM organizations WHERE slug = ?", slug));
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private Account account(String... roles) {
        String email = "a-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r "
                    + "WHERE u.email = ? AND r.code = ?", email, role);
        }
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        return new Account(id, accessToken(client.login(email, PASSWORD)));
    }

    private String organization(Account founder) {
        String slug = "it-adm-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        return data(client.postJson(ORGS, Map.of("slug", slug, "name", "Shop"), founder.token())).get("id").asText();
    }

    private ResponseEntity<String> action(Account caller, String orgId, String action) {
        return client.send(HttpMethod.POST, ADMIN_ORGS + "/" + orgId + "/" + action, null, caller.token());
    }

    private JsonNode data(ResponseEntity<String> response) {
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aNewSellerWaitsInTheQueueUntilApprovedThenAppearsActiveToItsOwner() {
        String orgId = organization(owner);

        JsonNode queue = data(client.get(ADMIN_ORGS + "?status=PENDING_APPROVAL&size=100", admin.token()));
        assertThat(queue.get("content").findValuesAsText("id")).contains(orgId);

        JsonNode approved = data(action(admin, orgId, "approve"));
        assertThat(approved.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(approved.get("statusChangedBy").asText()).isEqualTo(admin.id().toString());
        assertThat(approved.get("owners").asLong()).isEqualTo(1);
        assertThat(data(client.get(ORGS + "/" + orgId, owner.token())).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(action(admin, orgId, "approve").getBody()).contains("ORG_INVALID_STATUS");
    }

    @Test
    void suspensionMakesTheSellerReadOnlyWithoutRevealingWhyAndReactivationEndsIt(CapturedOutput logs) {
        String orgId = organization(owner);
        action(admin, orgId, "approve");
        String reason = "Counterfeit goods, case " + UUID.randomUUID();

        JsonNode suspended = data(client.postJson(ADMIN_ORGS + "/" + orgId + "/suspend", Map.of("reason", reason),
                admin.token()));

        assertThat(suspended.get("statusReason").asText()).isEqualTo(reason);
        ResponseEntity<String> ownerView = client.get(ORGS + "/" + orgId, owner.token());
        assertThat(ownerView.getBody()).contains("SUSPENDED").doesNotContain(reason);
        assertThat(client.send(HttpMethod.PUT, ORGS + "/" + orgId, Map.of("name", "Fresh start", "version", 1),
                owner.token()).getBody()).contains("ORG_INVALID_STATUS");
        assertThat(client.send(HttpMethod.POST, ORGS + "/" + orgId + "/close", null, owner.token()).getBody())
                .as("a suspended seller cannot walk away from the review").contains("ORG_INVALID_STATUS");

        assertThat(data(action(admin, orgId, "reactivate")).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(logs.getAll()).contains("Organisation suspended: organizationId=" + orgId).doesNotContain(reason);
    }

    @Test
    void staffCannotApproveOrRescueTheirOwnShop() {
        String orgId = organization(admin); // the administrator is also a seller

        ResponseEntity<String> selfApproval = action(admin, orgId, "approve");

        assertThat(selfApproval.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(selfApproval.getBody()).contains("ORG_CONFLICT_OF_INTEREST");
        assertThat(data(client.get(ADMIN_ORGS + "/" + orgId, admin.token())).get("status").asText())
                .isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void customersCannotUseThePlatformEndpoints() {
        String orgId = organization(owner);

        assertThat(client.get(ADMIN_ORGS, owner.token()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(action(owner, orgId, "approve").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anOwnerClosesTheirShopForGoodItsInvitationsDieAndTheSlugStaysTaken() {
        String orgId = organization(owner);
        Account manager = account();
        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role) VALUES (?::uuid, ?, 'MANAGER')",
                orgId, manager.id());
        client.postJson(ORGS + "/" + orgId + "/invitations", Map.of("email", "x-" + UUID.randomUUID() + DOMAIN,
                "role", "STAFF"), owner.token());

        assertThat(client.send(HttpMethod.POST, ORGS + "/" + orgId + "/close", null, manager.token()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode closed = data(client.send(HttpMethod.POST, ORGS + "/" + orgId + "/close", null, owner.token()));

        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_invitations WHERE organization_id = ?::uuid "
                + "AND revoked_at IS NULL AND accepted_at IS NULL", Long.class, orgId)).isZero();
        String slug = data(client.get(ORGS + "/" + orgId, owner.token())).get("slug").asText(); // still readable
        assertThat(client.postJson(ORGS, Map.of("slug", slug, "name", "Copycat"), manager.token()).getBody())
                .contains("ORG_SLUG_TAKEN");
        assertThat(client.send(HttpMethod.POST, ORGS + "/" + orgId + "/leave", null, owner.token()).getStatusCode())
                .as("the last owner may leave a closed organisation").isEqualTo(HttpStatus.NO_CONTENT);
    }
}
