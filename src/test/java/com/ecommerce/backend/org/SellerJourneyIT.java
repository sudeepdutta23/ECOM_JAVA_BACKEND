package com.ecommerce.backend.org;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Phase 4 as one story: a seller is founded, reviewed and approved, builds a team by invitation,
 * is suspended and reactivated, hands over ownership, loses its founder to an erasure request and
 * is finally closed. Then everything logged is audited.
 */
@ExtendWith(OutputCaptureExtension.class)
class SellerJourneyIT extends AbstractIntegrationTest {

    private static final String ORGS = "/api/v1/organizations";
    private static final String ADMIN_ORGS = "/api/v1/admin/organizations";
    private static final String DOMAIN = "@seller-journey.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private AuthTestClient client;
    private final List<String> secrets = new ArrayList<>();
    private final List<UUID> created = new ArrayList<>();
    private String slug;

    private record Person(UUID id, String email, String token) {
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM organizations WHERE slug = ?", slug);
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id)); // erased rows lost the domain
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private Person person(String name, String... roles) {
        String email = name + "-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r "
                    + "WHERE u.email = ? AND r.code = ?", email, role);
        }
        ResponseEntity<String> login = client.login(email, PASSWORD);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        secrets.addAll(List.of(email, accessToken(login), refreshCookieValue(login)));
        return new Person(id, email, accessToken(login));
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String inviteAndGetLink(Person inviter, String orgId, Person invitee, String role) {
        long before = mail.sentTo(invitee.email()).stream().filter(m -> m.subject().contains("invited")).count();
        data(client.postJson(ORGS + "/" + orgId + "/invitations", Map.of("email", invitee.email(), "role", role),
                inviter.token()));
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.sentTo(invitee.email()).stream()
                .filter(m -> m.subject().contains("invited")).count() > before);
        String token = RecordingEmailSender.tokenFrom(mail.sentTo(invitee.email()).stream()
                .filter(m -> m.subject().contains("invited")).reduce((first, second) -> second).orElseThrow());
        secrets.add(token);
        return token;
    }

    private ResponseEntity<String> post(String path, Person caller) {
        return client.send(HttpMethod.POST, path, null, caller.token());
    }

    @Test
    void aSellerIsFoundedApprovedStaffedSuspendedHandedOverAndClosedWithoutLeakingAnything(CapturedOutput logs) {
        client = new AuthTestClient(rest, mail);
        Person alice = person("alice");          // founder
        Person bob = person("bob");              // becomes manager, then owner
        Person dan = person("dan");              // staff
        Person eve = person("eve");              // outsider
        Person carol = person("carol", "ADMIN"); // platform staff
        slug = "journey-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Alice founds a seller. It waits for review; outsiders cannot even tell it exists.
        String orgId = data(client.postJson(ORGS, Map.of("slug", slug, "name", "Alice's Atelier"), alice.token()))
                .get("id").asText();
        assertThat(client.get(ORGS + "/" + orgId, eve.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 2. Carol reviews the queue and approves.
        assertThat(data(client.get(ADMIN_ORGS + "?status=PENDING_APPROVAL&size=100", carol.token()))
                .get("content").findValuesAsText("id")).contains(orgId);
        assertThat(data(post(ADMIN_ORGS + "/" + orgId + "/approve", carol)).get("status").asText()).isEqualTo("ACTIVE");

        // 3. Alice invites Bob as manager; Bob, as manager, invites Dan as staff, but no other manager.
        assertThat(data(client.postJson(ORGS + "/invitations/accept",
                Map.of("token", inviteAndGetLink(alice, orgId, bob, "MANAGER")), bob.token())).get("myRole").asText())
                .isEqualTo("MANAGER");
        data(client.postJson(ORGS + "/invitations/accept",
                Map.of("token", inviteAndGetLink(bob, orgId, dan, "STAFF")), dan.token()));
        assertThat(client.postJson(ORGS + "/" + orgId + "/invitations", Map.of("email", eve.email(), "role", "MANAGER"),
                bob.token()).getBody()).contains("ORG_RANK_DENIED");
        assertThat(data(client.get(ORGS + "/" + orgId + "/members", dan.token())).get("totalElements").asLong())
                .isEqualTo(3);

        // 4. Carol suspends the shop: read-only for everyone in it, and the reason stays internal.
        String reason = "Trademark complaint, case " + UUID.randomUUID();
        secrets.add(reason);
        data(client.postJson(ADMIN_ORGS + "/" + orgId + "/suspend", Map.of("reason", reason), carol.token()));
        assertThat(client.send(HttpMethod.PUT, ORGS + "/" + orgId, Map.of("name", "Renamed", "version", 1),
                bob.token()).getBody()).contains("ORG_INVALID_STATUS");
        assertThat(client.get(ORGS + "/" + orgId, alice.token()).getBody()).contains("SUSPENDED").doesNotContain(reason);
        data(post(ADMIN_ORGS + "/" + orgId + "/reactivate", carol));

        // 5. Alice wants to leave the platform; as the only owner she cannot, until Bob owns the shop too.
        Map<String, String> password = Map.of("password", PASSWORD);
        assertThat(client.postJson("/api/v1/auth/me/erase", password, alice.token()).getBody()).contains("ORG_LAST_OWNER");
        data(client.send(HttpMethod.PUT, ORGS + "/" + orgId + "/members/" + bob.id(), Map.of("role", "OWNER"),
                alice.token()));
        assertThat(client.postJson("/api/v1/auth/me/erase", password, alice.token()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(data(client.get(ORGS + "/" + orgId + "/members", bob.token())).get("totalElements").asLong())
                .as("Alice's membership went with her account").isEqualTo(2);

        // 6. Bob, now the only owner, closes the shop for good. Dan can still read its history.
        assertThat(data(post(ORGS + "/" + orgId + "/close", bob)).get("status").asText()).isEqualTo("CLOSED");
        assertThat(data(client.get(ORGS + "/" + orgId, dan.token())).get("status").asText()).isEqualTo("CLOSED");
        assertThat(client.postJson(ORGS, Map.of("slug", slug, "name", "Copycat"), eve.token()).getBody())
                .contains("ORG_SLUG_TAKEN");

        // 7. Log audit: the story is visible by ID, but no email, token, password or reason is.
        String output = logs.getAll();
        assertThat(output).contains("Organisation approved: organizationId=" + orgId)
                .contains("Organisation suspended: organizationId=" + orgId)
                .contains("Invitation accepted: organizationId=" + orgId)
                .contains("Organisation data of an erased account removed: userId=" + alice.id())
                .contains("Organisation closed by an owner: organizationId=" + orgId);
        for (String secret : secrets) {
            assertThat(output).as("logs must not contain a token, an email or a suspension reason").doesNotContain(secret);
        }
        assertThat(output).doesNotContain(PASSWORD).doesNotContain("$argon2id$");
    }
}
