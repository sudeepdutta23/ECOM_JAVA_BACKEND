package com.ecommerce.backend.audit;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The audit log: what is recorded, by whom, that it cannot be changed, and who may read it. */
class AuditIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@audit.test";
    private static final String AUDIT = "/api/v1/admin/audit";

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
    private UUID adminId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        adminId = account("ADMIN");
        adminToken = token(adminId);
    }

    @AfterEach
    void cleanUp() {
        slugs.forEach(s -> jdbc.update("DELETE FROM categories WHERE slug = ?", s));
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    private UUID account(String... roles) {
        String email = "au-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, role);
        }
        return id;
    }

    private String token(UUID id) {
        return accessToken(client.login(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id), PASSWORD));
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode entriesFor(UUID targetId) {
        return data(client.get(AUDIT + "?targetId=" + targetId, adminToken)).get("items");
    }

    @Test
    void anAdministratorsActionIsRecordedWithActorTargetAndRequestId() {
        UUID customer = account();

        ResponseEntity<String> suspend = client.postJson("/api/v1/admin/users/" + customer + "/suspend",
                Map.of("reason", "Chargeback fraud"), adminToken);
        data(suspend);
        data(client.send(HttpMethod.POST, "/api/v1/admin/users/" + customer + "/reactivate", null, adminToken));

        JsonNode entries = entriesFor(customer);
        assertThat(entries.findValuesAsText("action")).containsExactly("user.reactivate", "user.suspend");
        JsonNode suspended = entries.get(1);
        assertThat(suspended.get("actorId").asText()).isEqualTo(adminId.toString());
        assertThat(suspended.get("targetType").asText()).isEqualTo("user");
        assertThat(suspended.get("requestId").asText()).isEqualTo(suspend.getHeaders().getFirst("X-Request-Id"));
        assertThat(suspended.get("clientIp").asText()).isNotBlank();
        assertThat(entries.toString()).as("no reasons or personal data in the log").doesNotContain("Chargeback");
    }

    @Test
    void aRefusedActionLeavesNoEntry() {
        UUID other = account("ADMIN");

        // The last-admin rule does not apply here, but self-action does: an admin cannot suspend themselves.
        assertThat(client.postJson("/api/v1/admin/users/" + adminId + "/suspend", Map.of("reason", "x"), adminToken)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(entriesFor(adminId).findValuesAsText("action")).doesNotContain("user.suspend");
        assertThat(entriesFor(other)).isEmpty();
    }

    @Test
    void roleGrantsAndCategoryChangesAreRecordedWithTheirDetail() {
        UUID customer = account();
        UUID support = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'ADMIN'", UUID.class);
        assertThat(client.send(HttpMethod.PUT, "/api/v1/admin/users/" + customer + "/roles/" + support, null, adminToken)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String slug = "it-au-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        String categoryId = data(client.postJson("/api/v1/admin/categories", Map.of("slug", slug, "name", "Audit"), adminToken))
                .get("id").asText();

        assertThat(entriesFor(customer).get(0).get("detail").asText()).isEqualTo("role=" + support);
        assertThat(entriesFor(UUID.fromString(categoryId)).findValuesAsText("action")).containsExactly("category.create");
    }

    @Test
    void theLogIsAppendOnlyEvenForTheApplicationsOwnDatabaseAccount() {
        UUID customer = account();
        data(client.postJson("/api/v1/admin/users/" + customer + "/suspend", Map.of("reason", "x"), adminToken));

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET action = 'user.nothing' WHERE target_id = ?", customer))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE target_id = ?", customer))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_events"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThat(entriesFor(customer)).hasSize(1);
    }

    @Test
    void readingTheLogNeedsAuditReadAndPagesWithACursor() {
        UUID customer = account();
        for (int i = 0; i < 3; i++) {
            data(client.postJson("/api/v1/admin/users/" + customer + "/sign-out", null, adminToken));
        }

        assertThat(client.get(AUDIT, token(customer)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        JsonNode first = data(client.get(AUDIT + "?targetId=" + customer + "&size=2", adminToken));
        assertThat(first.get("items")).hasSize(2);
        String cursor = first.get("nextBefore").asText();
        JsonNode second = data(client.get(AUDIT + "?targetId=" + customer + "&size=2&before=" + cursor, adminToken));
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.has("nextBefore")).as("the last page has no cursor").isFalse();
        assertThat(second.at("/items/0/id").asText()).isNotIn(first.get("items").findValuesAsText("id"));
    }
}
