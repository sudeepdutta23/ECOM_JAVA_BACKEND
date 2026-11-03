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
 * Phase 5 as one story: a customer fills in a profile and an address book, support looks at it to
 * sort out a delivery, the customer downloads their data and finally erases the account. Nothing
 * personal is left in the database, and nothing personal was ever logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProfileJourneyIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@profile-journey.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID idOf(String email) {
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        return id;
    }

    @Test
    void aCustomersPersonalDataLivesAndEndsWithoutTraces(CapturedOutput logs) {
        AuthTestClient client = new AuthTestClient(rest, mail);
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "grace-" + marker + DOMAIN;
        String familyName = "Hopper" + marker;
        String street = "Navy Yard " + marker;
        String phone = "+1202555" + (1000 + Math.abs(marker.hashCode() % 9000));
        client.registerAndVerify(email);
        UUID grace = idOf(email);
        String token = accessToken(client.login(email, PASSWORD));
        String supportEmail = "support-" + marker + DOMAIN;
        client.registerAndVerify(supportEmail);
        UUID support = idOf(supportEmail);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'ADMIN'", support);
        String supportToken = accessToken(client.login(supportEmail, PASSWORD));

        // 1. Grace fills in her profile and two addresses; the second becomes the shipping default.
        data(client.send(HttpMethod.PUT, "/api/v1/profile", Map.of("givenName", "Grace", "familyName", familyName,
                "phoneNumber", phone, "preferredLocale", "en-US", "timeZone", "America/New_York"), token));
        data(client.postJson("/api/v1/profile/addresses", Map.of("recipientName", "Grace " + familyName,
                "line1", street, "city", "Arlington", "region", "VA", "postalCode", "22202", "countryCode", "US"), token));
        String office = data(client.postJson("/api/v1/profile/addresses", Map.of("recipientName", "Grace " + familyName,
                "line1", "1 Pentagon " + marker, "city", "Washington", "region", "DC", "postalCode", "20301",
                "countryCode", "US", "defaultShipping", true), token)).get("id").asText();

        // 2. Support looks at it to sort out a delivery.
        JsonNode seen = data(client.get("/api/v1/admin/users/" + grace + "/profile", supportToken));
        assertThat(seen.get("addresses")).hasSize(2);
        assertThat(seen.get("addresses").findValuesAsText("id")).contains(office);

        // 3. Grace downloads her data: everything is there, in one document.
        JsonNode export = data(client.get("/api/v1/auth/me/export", token));
        assertThat(export.at("/profile/familyName").asText()).isEqualTo(familyName);
        assertThat(export.at("/profile/addresses")).hasSize(2);
        assertThat(export.at("/account/email").asText()).isEqualTo(email);

        // 4. She erases her account. Nothing personal remains, and the old token cannot write again.
        assertThat(client.postJson("/api/v1/auth/me/erase", Map.of("password", PASSWORD), token).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profiles WHERE user_id = ?", Long.class, grace)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_addresses WHERE user_id = ?", Long.class, grace)).isZero();
        assertThat(client.get("/api/v1/admin/users/" + grace + "/profile", supportToken).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.send(HttpMethod.PUT, "/api/v1/profile", Map.of("givenName", "Grace"), token).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // 5. Log audit: the story is visible by ID; the person is not.
        String output = logs.getAll();
        assertThat(output).contains("Profile saved: userId=" + grace)
                .contains("Address created: userId=" + grace)
                .contains("Personal data viewed by staff: userId=" + grace + ", by=" + support)
                .contains("Personal data exported: userId=" + grace);
        for (String personal : List.of(email, familyName, street, phone, "1 Pentagon " + marker, "America/New_York")) {
            assertThat(output).as("logs must not contain personal data").doesNotContain(personal);
        }
    }
}
