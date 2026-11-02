package com.ecommerce.backend.auth;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The admin user list and detail over real HTTP and real PostgreSQL. */
class UserAdminIT extends AbstractIntegrationTest {

    private static final String USERS = "/api/v1/admin/users";
    private static final String DOMAIN = "@user-admin.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private AuthTestClient client;
    private String adminToken;
    private UUID adminId;
    /** Unique per test, so filters can be checked exactly although other tests leave accounts behind. */
    private String run;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        run = "ua" + UUID.randomUUID().toString().substring(0, 8);
        String adminEmail = "admin-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(adminEmail);
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.id, r.id FROM users u, roles r WHERE u.email = ? AND r.code = 'ADMIN'""", adminEmail);
        adminToken = accessToken(client.login(adminEmail, PASSWORD));
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, adminEmail);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    /** Registers (and verifies) accounts in this order, so the LAST one is the newest. */
    private List<UUID> accounts(String... localParts) {
        List<UUID> ids = new ArrayList<>();
        for (String localPart : localParts) {
            String email = run + localPart + DOMAIN;
            client.registerAndVerify(email);
            ids.add(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email));
        }
        return ids;
    }

    private JsonNode list(String query) throws Exception {
        ResponseEntity<String> response = client.get(USERS + query, adminToken);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    /** Raw value: TestRestTemplate percent-encodes the URL itself ({@code %} becomes {@code %25}). */
    private static String emailQuery(String prefix) {
        return "?email=" + prefix;
    }

    private static List<String> emails(JsonNode page) {
        List<String> emails = new ArrayList<>();
        page.get("content").forEach(row -> emails.add(row.get("email").asText()));
        return emails;
    }

    // ------------------------------------------------------------------ list and search

    @Test
    void emailPrefixSearchIsCaseInsensitiveAndNewestFirst() throws Exception {
        accounts("-anna", "-bob", "-alex");

        JsonNode page = list(emailQuery("  " + run.toUpperCase() + "-A"));

        assertThat(emails(page)).containsExactly(run + "-alex" + DOMAIN, run + "-anna" + DOMAIN);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void wildcardsInTheSearchMatchLiterally() throws Exception {
        accounts("-a_1", "-ax1", "-50%off");

        assertThat(emails(list(emailQuery(run + "-a_")))).containsExactly(run + "-a_1" + DOMAIN);
        assertThat(emails(list(emailQuery(run + "-50%")))).containsExactly(run + "-50%off" + DOMAIN);
        assertThat(list(emailQuery(run + "%")).get("totalElements").asLong()).isZero();
    }

    @Test
    void pagesAreConsistentAndTotalsCorrect() throws Exception {
        accounts("-1", "-2", "-3", "-4", "-5");

        JsonNode first = list(emailQuery(run) + "&size=2");
        JsonNode third = list(emailQuery(run) + "&size=2&page=2");

        assertThat(emails(first)).containsExactly(run + "-5" + DOMAIN, run + "-4" + DOMAIN);
        assertThat(emails(third)).containsExactly(run + "-1" + DOMAIN);
        assertThat(first.get("totalElements").asLong()).isEqualTo(5);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(list(emailQuery(run) + "&size=2&page=9").get("content")).isEmpty(); // past the end: empty, not an error
    }

    @Test
    void statusFilterCombinesWithTheEmailFilter() throws Exception {
        List<UUID> ids = accounts("-ok", "-blocked");
        jdbc.update("UPDATE users SET status = 'SUSPENDED', status_reason = 'Abuse' WHERE id = ?", ids.get(1));

        JsonNode suspended = list(emailQuery(run) + "&status=SUSPENDED");

        assertThat(emails(suspended)).containsExactly(run + "-blocked" + DOMAIN);
        assertThat(suspended.get("content").get(0).has("statusReason")).isFalse(); // only in the detail view
    }

    @Test
    void listNeverExposesCredentials() {
        accounts("-secret");

        String body = client.get(USERS + emailQuery(run), adminToken).getBody();

        assertThat(body).doesNotContain("argon2").doesNotContain("passwordHash").doesNotContain("password");
    }

    // ------------------------------------------------------------------ detail

    @Test
    void detailShowsRolesSessionsLockAndTheStatusChange() throws Exception {
        UUID id = accounts("-detail").get(0);
        String email = run + "-detail" + DOMAIN;
        client.login(email, PASSWORD);
        client.login(email, PASSWORD); // two devices
        jdbc.update("""
                UPDATE users SET failed_login_count = 5, locked_until = now() + interval '10 minutes',
                       status_reason = 'Checked by support', status_changed_at = now(), status_changed_by = ?
                WHERE id = ?""", adminId, id);

        ResponseEntity<String> response = client.get(USERS + "/" + id, adminToken);
        JsonNode detail = mapper.readTree(response.getBody()).get("data");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.get("email").asText()).isEqualTo(email);
        assertThat(detail.get("roles").toString()).isEqualTo("[\"CUSTOMER\"]");
        assertThat(detail.get("activeSessions").asLong()).isEqualTo(2);
        assertThat(detail.get("failedLoginCount").asInt()).isEqualTo(5);
        assertThat(detail.has("lockedUntil")).isTrue();
        assertThat(detail.get("statusReason").asText()).isEqualTo("Checked by support");
        assertThat(detail.get("statusChangedBy").asText()).isEqualTo(adminId.toString());
        assertThat(response.getBody()).doesNotContain("argon2").doesNotContain("token");
    }

    @Test
    void anExpiredLockIsNotShownAsALock() throws Exception {
        UUID id = accounts("-expired").get(0);
        jdbc.update("UPDATE users SET locked_until = now() - interval '1 minute' WHERE id = ?", id);

        JsonNode detail = mapper.readTree(client.get(USERS + "/" + id, adminToken).getBody()).get("data");

        assertThat(detail.has("lockedUntil")).isFalse();
    }

    @Test
    void unknownUserIs404() {
        assertThat(client.get(USERS + "/" + UUID.randomUUID(), adminToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void customersCannotListOrViewAccounts() {
        String email = run + "-customer" + DOMAIN;
        client.registerAndVerify(email);
        String customerToken = accessToken(client.login(email, PASSWORD));

        assertThat(client.get(USERS, customerToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(client.get(USERS + "/" + UUID.randomUUID(), customerToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
