package com.ecommerce.backend.rbac;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Role management over real HTTP, with the escalation guards enforced against real tokens. */
class RoleManagementIT extends AbstractIntegrationTest {

    private static final String ROLES = "/api/v1/admin/roles";

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

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        adminToken = signedInWith("ADMIN");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%@roles.test'");      // cascades user_roles
        jdbc.update("DELETE FROM roles WHERE code LIKE 'IT\\_%' AND NOT system_role");
    }

    /** A fresh, verified user holding the given role (granted directly in SQL), signed in. */
    private String signedInWith(String roleCode) {
        String email = "user-" + UUID.randomUUID() + "@roles.test";
        client.registerAndVerify(email);
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.id, r.id FROM users u, roles r WHERE u.email = ? AND r.code = ?
                ON CONFLICT DO NOTHING""", email, roleCode); // registration already granted CUSTOMER
        return accessToken(client.login(email, PASSWORD));
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("data");
    }

    private ResponseEntity<String> create(String token, String code, List<String> permissions) {
        return client.send(HttpMethod.POST, ROLES, Map.of("code", code, "name", "Role " + code,
                "permissions", permissions), token);
    }

    private String roleId(String code) {
        return jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, code).toString();
    }

    // ------------------------------------------------------------------ CRUD

    @Test
    void adminCreatesReadsUpdatesAndDeletesARole() throws Exception {
        ResponseEntity<String> created = create(adminToken, "IT_SUPPORT", List.of("role:read"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = data(created).get("id").asText();
        assertThat(created.getHeaders().getLocation()).hasToString(ROLES + "/" + id);
        assertThat(data(created).get("version").asLong()).isZero();

        assertThat(client.get(ROLES + "/" + id, adminToken).getBody()).contains("\"IT_SUPPORT\"");
        assertThat(client.get(ROLES, adminToken).getBody()).contains("\"IT_SUPPORT\"").contains("\"ADMIN\"");

        ResponseEntity<String> updated = client.send(HttpMethod.PUT, ROLES + "/" + id, Map.of(
                "name", "Support agent", "permissions", List.of("role:read", "role:write"), "version", 0), adminToken);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(updated).get("version").asLong()).isEqualTo(1);
        assertThat(data(updated).get("permissions").toString()).isEqualTo("[\"role:read\",\"role:write\"]");

        assertThat(client.send(HttpMethod.DELETE, ROLES + "/" + id, null, adminToken).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.get(ROLES + "/" + id, adminToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void staleVersionCannotOverwriteSomeoneElsesChange() throws Exception {
        String id = data(create(adminToken, "IT_STALE", List.of())).get("id").asText();
        client.send(HttpMethod.PUT, ROLES + "/" + id, Map.of("name", "First edit", "permissions", List.of(), "version", 0), adminToken);

        ResponseEntity<String> second = client.send(HttpMethod.PUT, ROLES + "/" + id,
                Map.of("name", "Second edit", "permissions", List.of(), "version", 0), adminToken);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("CONCURRENT_MODIFICATION");
        assertThat(jdbc.queryForObject("SELECT name FROM roles WHERE id = ?::uuid", String.class, id)).isEqualTo("First edit");
    }

    @Test
    void duplicateCodeIs409() {
        create(adminToken, "IT_DUP", List.of());

        ResponseEntity<String> again = create(adminToken, "IT_DUP", List.of());

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("DUPLICATE_RESOURCE");
    }

    @Test
    void unknownPermissionIs400OnThePermissionsField() {
        ResponseEntity<String> response = create(adminToken, "IT_BAD", List.of("order:refund"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"field\":\"permissions\"").contains("order:refund");
    }

    // ------------------------------------------------------------------ protection rules

    @Test
    void systemRolesCannotBeChangedOrDeleted() {
        String adminRoleId = roleId("ADMIN");

        ResponseEntity<String> edit = client.send(HttpMethod.PUT, ROLES + "/" + adminRoleId,
                Map.of("name", "Hacked", "permissions", List.of(), "version", 0), adminToken);
        ResponseEntity<String> delete = client.send(HttpMethod.DELETE, ROLES + "/" + roleId("CUSTOMER"), null, adminToken);

        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(edit.getBody()).contains("RBAC_SYSTEM_ROLE");
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                + "WHERE r.code = 'ADMIN'", Integer.class)).isEqualTo(Permission.values().length);
    }

    @Test
    void roleInUseCannotBeDeleted() throws Exception {
        String id = data(create(adminToken, "IT_IN_USE", List.of())).get("id").asText();
        signedInWith("IT_IN_USE");

        ResponseEntity<String> response = client.send(HttpMethod.DELETE, ROLES + "/" + id, null, adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("RBAC_ROLE_IN_USE").contains("1 user(s)");
    }

    @Test
    void editorWithoutRoleAssignCannotCreateARoleThatGrantsIt() {
        // An "editor" may write roles but not assign them.
        create(adminToken, "IT_EDITOR", List.of("role:read", "role:write"));
        String editorToken = signedInWith("IT_EDITOR");

        ResponseEntity<String> sneaky = create(editorToken, "IT_SNEAKY", List.of("role:assign"));
        ResponseEntity<String> fine = create(editorToken, "IT_READER", List.of("role:read"));

        assertThat(sneaky.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(sneaky.getBody()).contains("RBAC_ESCALATION_DENIED").contains("role:assign");
        assertThat(fine.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void editorCannotEditTheAdminLevelRoleItCannotMatch() throws Exception {
        create(adminToken, "IT_EDITOR", List.of("role:read", "role:write"));
        String strongId = data(create(adminToken, "IT_STRONG", List.of("role:assign"))).get("id").asText();
        String editorToken = signedInWith("IT_EDITOR");

        ResponseEntity<String> strip = client.send(HttpMethod.PUT, ROLES + "/" + strongId,
                Map.of("name", "Stripped", "permissions", List.of(), "version", 0), editorToken);

        assertThat(strip.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(strip.getBody()).contains("RBAC_ESCALATION_DENIED");
    }

    @Test
    void customerCannotReadRoles() {
        assertThat(client.get(ROLES, signedInWith("CUSTOMER")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ effect on holders

    @Test
    void holdersGainNewPermissionsAtTheirNextRefresh() throws Exception {
        String id = data(create(adminToken, "IT_VIEWER", List.of())).get("id").asText();
        String email = "viewer-" + UUID.randomUUID() + "@roles.test";
        client.registerAndVerify(email);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT u.id, ?::uuid FROM users u WHERE u.email = ?", id, email);
        ResponseEntity<String> login = client.login(email, PASSWORD);
        assertThat(client.get(ROLES, accessToken(login)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        client.send(HttpMethod.PUT, ROLES + "/" + id,
                Map.of("name", "Viewer", "permissions", List.of("role:read"), "version", 0), adminToken);
        String refreshed = accessToken(client.refresh(refreshCookieValue(login)));

        assertThat(client.get(ROLES, refreshed).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
