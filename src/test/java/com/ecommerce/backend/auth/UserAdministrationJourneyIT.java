package com.ecommerce.backend.auth;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Phase 3 as one story: an administrator sets up a support role; a customer manages their own
 * devices; support finds, suspends, reactivates and unlocks the customer within its limits; the
 * administrator signs the customer out; the customer erases the account. Then the logs are audited.
 */
@ExtendWith(OutputCaptureExtension.class)
class UserAdministrationJourneyIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@users-journey.test";
    private static final String USERS = "/api/v1/admin/users";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private final List<String> secrets = new ArrayList<>();
    private final List<UUID> created = new ArrayList<>();
    private AuthTestClient client;

    private record Person(UUID id, String email) {
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id)); // erased rows lost the domain
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
        jdbc.update("DELETE FROM roles WHERE code = 'JOURNEY_USER_SUPPORT'");
    }

    private Person register(String name) {
        String email = name + "-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        secrets.add(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        return new Person(id, email);
    }

    /** Signs in and remembers the tokens, so the log audit can look for them. */
    private ResponseEntity<String> login(Person person) {
        ResponseEntity<String> login = client.login(person.email(), PASSWORD);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        secrets.addAll(List.of(accessToken(login), refreshCookieValue(login)));
        return login;
    }

    private ResponseEntity<String> post(String path, String token) {
        return client.send(HttpMethod.POST, path, null, token);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private JsonNode detail(Person person, String token) throws Exception {
        return data(client.get(USERS + "/" + person.id(), token));
    }

    @Test
    void supportManagesACustomerWithinItsLimitsAndTheCustomerLeavesWithoutATrace(CapturedOutput logs) throws Exception {
        client = new AuthTestClient(rest, mail);
        Person alice = register("alice");
        Person bob = register("bob");
        Person carol = register("carol");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'ADMIN'", alice.id());
        String aliceToken = accessToken(login(alice));

        // 1. The administrator creates a support role (read and manage, but not erase) and gives it to bob.
        ResponseEntity<String> role = client.send(HttpMethod.POST, "/api/v1/admin/roles", Map.of(
                "code", "JOURNEY_USER_SUPPORT", "name", "User support",
                "permissions", List.of("user:read", "user:manage")), aliceToken);
        assertThat(role.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID supportRole = UUID.fromString(mapper.readTree(role.getBody()).at("/data/id").asText());
        assertThat(client.send(HttpMethod.PUT, USERS + "/" + bob.id() + "/roles/" + supportRole, null, aliceToken)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String bobToken = accessToken(login(bob));

        // 2. Carol signs in on two devices and signs out the phone she lost.
        ResponseEntity<String> phone = login(carol);
        ResponseEntity<String> laptop = login(carol);
        JsonNode sessions = data(client.get("/api/v1/auth/me/sessions", accessToken(laptop)));
        assertThat(sessions).hasSize(2);
        String phoneSession = sessions.get(1).get("sessionId").asText();
        assertThat(client.send(HttpMethod.DELETE, "/api/v1/auth/me/sessions/" + phoneSession, null, accessToken(laptop))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.refresh(refreshCookieValue(phone)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 3. Support finds carol by the start of her email and sees one live device.
        JsonNode found = data(client.get(USERS + "?email=" + carol.email().substring(0, 12), bobToken));
        assertThat(found.get("totalElements").asLong()).isEqualTo(1);
        assertThat(found.at("/content/0/id").asText()).isEqualTo(carol.id().toString());
        assertThat(detail(carol, bobToken).get("activeSessions").asLong()).isEqualTo(1);

        // 4. Support suspends carol. Her laptop is signed out, and sign-in is refused without the reason.
        String reason = "Chargeback ring, case " + UUID.randomUUID();
        secrets.add(reason);
        assertThat(data(client.postJson(USERS + "/" + carol.id() + "/suspend", Map.of("reason", reason), bobToken))
                .get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(client.refresh(refreshCookieValue(laptop)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> refused = client.login(carol.email(), PASSWORD);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).doesNotContain(reason);

        // 5. Support's limits: not the administrator (escalation), and no erasure (separate permission).
        assertThat(client.postJson(USERS + "/" + alice.id() + "/suspend", Map.of("reason", "Takeover"), bobToken)
                .getBody()).contains("RBAC_ESCALATION_DENIED");
        assertThat(post(USERS + "/" + carol.id() + "/erase", bobToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 6. The dispute is settled: support reactivates carol, and she signs in again.
        assertThat(data(post(USERS + "/" + carol.id() + "/reactivate", bobToken)).get("status").asText())
                .isEqualTo("ACTIVE");
        login(carol);

        // 7. Carol mistypes her password until she is locked out; support unlocks her early.
        for (int attempt = 0; attempt < 5; attempt++) {
            client.login(carol.email(), "Wrong-Password-" + attempt);
        }
        assertThat(client.login(carol.email(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(detail(carol, bobToken).has("lockedUntil")).isTrue();
        assertThat(data(post(USERS + "/" + carol.id() + "/unlock", bobToken)).has("lockedUntil")).isFalse();
        String carolToken = accessToken(login(carol));

        // 8. After a reported theft, the administrator signs carol out everywhere.
        assertThat(data(post(USERS + "/" + carol.id() + "/sign-out", aliceToken)).get("activeSessions").asLong())
                .isZero();

        // 9. Carol erases her account. Support can no longer find her; the record remains, anonymous.
        assertThat(client.postJson("/api/v1/auth/me/erase", Map.of("password", PASSWORD), carolToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(data(client.get(USERS + "?email=" + carol.email().substring(0, 12), bobToken))
                .get("totalElements").asLong()).isZero();
        JsonNode erased = detail(carol, aliceToken);
        assertThat(erased.get("status").asText()).isEqualTo("ERASED");
        assertThat(erased.get("email").asText()).isEqualTo("erased-" + carol.id() + "@erased.invalid");
        assertThat(erased.get("roles")).isEmpty();

        // 10. Log audit. First prove the capture works, then that nothing secret or personal is in it.
        String output = logs.getAll();
        assertThat(output).contains("Account suspended: userId=" + carol.id())
                .contains("Account unlocked: userId=" + carol.id())
                .contains("Account erased by owner: userId=" + carol.id());
        for (String secret : secrets) {
            assertThat(output).as("logs must not contain a token, a full email or a suspension reason")
                    .doesNotContain(secret);
        }
        assertThat(output).doesNotContain(PASSWORD).doesNotContain("$argon2id$");
    }
}
