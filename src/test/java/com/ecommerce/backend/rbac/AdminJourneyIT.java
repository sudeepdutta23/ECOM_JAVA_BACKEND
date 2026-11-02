package com.ecommerce.backend.rbac;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.rbac.service.RoleAssignmentService;
import com.ecommerce.backend.rbac.service.RoleAssignmentService.BootstrapOutcome;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Phase 2 as one story: the first administrator is bootstrapped, delegates work through a
 * custom role, hands over to a successor and is demoted. Then everything logged is audited.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminJourneyIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@admin-journey.test";
    private static final String ROLES = "/api/v1/admin/roles";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private RoleAssignmentService assignments;

    private final List<String> secrets = new ArrayList<>();
    private AuthTestClient client;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
        jdbc.update("DELETE FROM roles WHERE code = 'JOURNEY_SUPPORT'");
    }

    private record Person(UUID id, String email) {
    }

    private Person register(String name) {
        String email = name + "-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        secrets.add(email);
        return new Person(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email), email);
    }

    /** Signs in and remembers the tokens, so the log audit can look for them. */
    private ResponseEntity<String> login(Person person) {
        ResponseEntity<String> login = client.login(person.email(), PASSWORD);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        secrets.addAll(List.of(accessToken(login), refreshCookieValue(login)));
        return login;
    }

    private ResponseEntity<String> refresh(ResponseEntity<String> login) {
        ResponseEntity<String> refreshed = client.refresh(refreshCookieValue(login));
        secrets.addAll(List.of(accessToken(refreshed), refreshCookieValue(refreshed)));
        return refreshed;
    }

    private String assignment(Person user, UUID roleId) {
        return "/api/v1/admin/users/" + user.id() + "/roles/" + roleId;
    }

    private UUID adminRoleId() {
        return jdbc.queryForObject("SELECT id FROM roles WHERE code = 'ADMIN'", UUID.class);
    }

    @Test
    void firstAdminDelegatesHandsOverAndIsDemotedWithoutLeakingSecrets(CapturedOutput logs) throws Exception {
        client = new AuthTestClient(rest, mail);
        // The bootstrap only acts on an empty admin set; other tests leave admins behind.
        jdbc.update("DELETE FROM user_roles WHERE role_id = (SELECT id FROM roles WHERE code = 'ADMIN')");
        Person alice = register("alice");
        Person bob = register("bob");
        Person carol = register("carol");

        // 1. Bootstrap: alice becomes the first admin. Nobody else can use the setting afterwards.
        assertThat(assignments.bootstrapAdmin(alice.email())).isEqualTo(BootstrapOutcome.GRANTED);
        assertThat(assignments.bootstrapAdmin(bob.email())).isEqualTo(BootstrapOutcome.ADMIN_ALREADY_EXISTS);
        ResponseEntity<String> aliceLogin = login(alice);
        String aliceToken = accessToken(aliceLogin);
        assertThat(client.get("/api/v1/auth/me", aliceToken).getBody())
                .contains("\"ADMIN\"").contains("\"role:assign\"");

        // 2. Delegation: a read-only support role for bob. Bob can read roles but not write them.
        ResponseEntity<String> created = client.send(HttpMethod.POST, ROLES, Map.of(
                "code", "JOURNEY_SUPPORT", "name", "Support", "permissions", List.of("role:read")), aliceToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID supportId = UUID.fromString(mapper.readTree(created.getBody()).at("/data/id").asText());
        assertThat(client.send(HttpMethod.PUT, assignment(bob, supportId), null, aliceToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        String bobToken = accessToken(login(bob));
        assertThat(client.get(ROLES, bobToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(client.send(HttpMethod.POST, ROLES, Map.of("code", "JOURNEY_BOB", "name", "Bob's",
                "permissions", List.of()), bobToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 3. The only admin cannot leave: not by deactivating, not by demoting themselves.
        ResponseEntity<String> leave = client.postJson("/api/v1/auth/me/deactivate", Map.of("password", PASSWORD), aliceToken);
        assertThat(leave.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(leave.getBody()).contains("RBAC_LAST_ADMIN");
        assertThat(client.send(HttpMethod.DELETE, assignment(alice, adminRoleId()), null, aliceToken).getBody())
                .contains("RBAC_SELF_MODIFICATION_DENIED");

        // 4. Hand-over: alice promotes carol, and carol demotes alice.
        assertThat(client.send(HttpMethod.PUT, assignment(carol, adminRoleId()), null, aliceToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        String carolToken = accessToken(login(carol));
        assertThat(client.send(HttpMethod.DELETE, assignment(alice, adminRoleId()), null, carolToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // 5. The documented trade-off (ADR-010): alice's current token keeps its claims until it
        //    expires (at most 15 minutes), but every management action re-reads her permissions...
        assertThat(client.get(ROLES, aliceToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> stale = client.send(HttpMethod.PUT, assignment(bob, adminRoleId()), null, aliceToken);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(stale.getBody()).contains("RBAC_ESCALATION_DENIED");
        //    ...and her next refresh carries no admin powers at all.
        String aliceRefreshed = accessToken(refresh(aliceLogin));
        assertThat(client.get(ROLES, aliceRefreshed).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 6. Clean-up by the new admin: revoke the support role, then delete it.
        assertThat(client.send(HttpMethod.DELETE, assignment(bob, supportId), null, carolToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.send(HttpMethod.DELETE, ROLES + "/" + supportId, null, carolToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // 7. Now alice may leave; carol, the last admin, may not.
        assertThat(client.postJson("/api/v1/auth/me/deactivate", Map.of("password", PASSWORD), aliceRefreshed)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.postJson("/api/v1/auth/me/deactivate", Map.of("password", PASSWORD), carolToken)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 8. Log audit. First prove the capture works, then that nothing secret is in it.
        String output = logs.getAll();
        assertThat(output).contains("Admin bootstrap: ADMIN granted to userId=" + alice.id())
                .contains("Role revoked: userId=" + alice.id() + ", role=ADMIN, by=" + carol.id());
        for (String secret : secrets) {
            assertThat(output).as("logs must not contain a token or a full email").doesNotContain(secret);
        }
        assertThat(output).doesNotContain(PASSWORD).doesNotContain("$argon2id$");
    }
}
