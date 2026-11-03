package com.ecommerce.backend.profile;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** "Download my data" across every module, and support access to a customer's personal data. */
@ExtendWith(OutputCaptureExtension.class)
class PersonalDataIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@personal-data.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private AuthTestClient client;
    private final List<UUID> created = new ArrayList<>();
    private final List<String> slugs = new ArrayList<>();

    private record Account(UUID id, String email, String token) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
    }

    @AfterEach
    void cleanUp() {
        slugs.forEach(slug -> jdbc.update("DELETE FROM organizations WHERE slug = ?", slug));
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
        jdbc.update("DELETE FROM roles WHERE code LIKE 'IT\\_PD\\_%'");
    }

    private Account account(String... roles) {
        String email = "pd-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r "
                    + "WHERE u.email = ? AND r.code = ?", email, role);
        }
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        return new Account(id, email, accessToken(client.login(email, PASSWORD)));
    }

    /** A custom role holding exactly these permissions. */
    private String role(String... permissions) {
        String code = "IT_PD_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO roles (id, code, name, system_role) VALUES (?, ?, 'Support', false)", id, code);
        for (String permission : permissions) {
            jdbc.update("INSERT INTO role_permissions (role_id, permission_code) VALUES (?, ?)", id, permission);
        }
        return code;
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void fillProfile(Account account, String street) {
        data(client.send(HttpMethod.PUT, "/api/v1/profile",
                Map.of("givenName", "Ada", "familyName", "Lovelace", "timeZone", "Europe/London"), account.token()));
        assertThat(client.postJson("/api/v1/profile/addresses", Map.of("recipientName", "Ada Lovelace", "line1", street,
                "city", "London", "postalCode", "SW1A 1AA", "countryCode", "GB"), account.token()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theExportHoldsEveryModulesDataAboutTheCallerAndNoSecrets(CapturedOutput logs) {
        Account ada = account();
        fillProfile(ada, "10 Downing Street");
        String slug = "it-pd-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Ada's Engines"), ada.token());

        ResponseEntity<String> response = client.get("/api/v1/auth/me/export", ada.token());

        JsonNode export = data(response);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(export.at("/account/id").asText()).isEqualTo(ada.id().toString());
        assertThat(export.at("/account/email").asText()).isEqualTo(ada.email());
        assertThat(export.get("roles").toString()).contains("CUSTOMER");
        assertThat(export.get("sessions")).hasSize(1);
        assertThat(export.at("/sessions/0/current").asBoolean()).as("this device is marked").isTrue();
        assertThat(export.at("/organizations/0/slug").asText()).isEqualTo(slug);
        assertThat(export.at("/organizations/0/role").asText()).isEqualTo("OWNER");
        assertThat(export.at("/profile/givenName").asText()).isEqualTo("Ada");
        assertThat(export.at("/profile/addresses/0/line1").asText()).isEqualTo("10 Downing Street");

        String body = response.getBody();
        assertThat(body).doesNotContain("$argon2", "{argon2}", "passwordHash", "tokenHash", "token_hash");
        assertThat(logs.getAll()).contains("Personal data exported: userId=" + ada.id())
                .doesNotContain("10 Downing Street");
    }

    @Test
    void anErasedAccountsStillValidTokenExportsNothing() {
        Account ada = account();
        fillProfile(ada, "Street 1");
        client.postJson("/api/v1/auth/me/erase", Map.of("password", PASSWORD), ada.token());

        ResponseEntity<String> response = client.get("/api/v1/auth/me/export", ada.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).doesNotContain("Street 1");
    }

    @Test
    void supportWithProfileReadSeesTheCustomerAndEveryViewIsLogged(CapturedOutput logs) {
        Account customer = account();
        fillProfile(customer, "221B Baker Street");
        Account support = account(role("profile:read"));
        String path = "/api/v1/admin/users/" + customer.id() + "/profile";

        JsonNode seen = data(client.get(path, support.token()));

        assertThat(seen.get("givenName").asText()).isEqualTo("Ada");
        assertThat(seen.at("/addresses/0/line1").asText()).isEqualTo("221B Baker Street");
        assertThat(logs.getAll()).contains("Personal data viewed by staff: userId=" + customer.id() + ", by=" + support.id())
                .doesNotContain("221B Baker Street");
    }

    @Test
    void accountAdministrationAloneDoesNotOpenPersonalData() {
        Account customer = account();
        fillProfile(customer, "Street 1");
        Account accountsTeam = account(role("user:read", "user:manage"));
        Account shopper = account();
        String path = "/api/v1/admin/users/" + customer.id() + "/profile";

        assertThat(client.get(path, accountsTeam.token()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(client.get(path, shopper.token()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void supportGetsTheSame404ForUnknownAndErasedAccounts() {
        Account admin = account("ADMIN");
        Account erased = account();
        fillProfile(erased, "Street 1");
        client.postJson("/api/v1/auth/me/erase", Map.of("password", PASSWORD), erased.token());

        ResponseEntity<String> forErased = client.get("/api/v1/admin/users/" + erased.id() + "/profile", admin.token());
        ResponseEntity<String> forUnknown = client.get("/api/v1/admin/users/" + UUID.randomUUID() + "/profile",
                admin.token());

        assertThat(forErased.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(forUnknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Same body apart from the request's own path, timestamp and request ID.
        assertThat(AuthTestClient.stableBody(forErased).replace(erased.id().toString(), "{id}"))
                .isEqualTo(AuthTestClient.stableBody(forUnknown).replaceAll("users/[0-9a-f-]{36}/", "users/{id}/"));
    }
}
