package com.ecommerce.backend.rbac;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.rbac.entity.Role;
import com.ecommerce.backend.rbac.repository.RoleRepository;
import com.ecommerce.backend.rbac.service.AuthorizationService;
import com.ecommerce.backend.rbac.service.UserAuthorities;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Roles in the database, claims in the token, and permission checks on a real endpoint. */
class AuthorizationIT extends AbstractIntegrationTest {

    private static final String ADMIN_ENDPOINT = "/api/v1/admin/permissions";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private AuthorizationService authorization;

    @Autowired
    private TransactionTemplate tx;

    private AuthTestClient client;
    private String email;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        email = "user-" + UUID.randomUUID() + "@authz.test";
        client.registerAndVerify(email);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%@authz.test'");
    }

    private UUID userId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private void grant(String roleCode) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", userId(), roleCode);
    }

    private JsonNode claims(String jwt) throws Exception {
        return mapper.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    @Test
    void registrationGrantsCustomerAsASystemAssignment() {
        assertThat(jdbc.queryForList("""
                SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?""",
                String.class, userId())).containsExactly("CUSTOMER");
        assertThat(jdbc.queryForObject("SELECT assigned_by FROM user_roles WHERE user_id = ?", UUID.class, userId()))
                .isNull();
    }

    @Test
    void customerTokenCarriesTheRoleButNoPermissions() throws Exception {
        JsonNode claims = claims(accessToken(client.login(email, PASSWORD)));

        assertThat(claims.get("roles").toString()).isEqualTo("[\"CUSTOMER\"]");
        assertThat(claims.get("perms").toString()).isEqualTo("[]");
    }

    @Test
    void customerIsRefusedTheAdminEndpoint() {
        ResponseEntity<String> response = client.get(ADMIN_ENDPOINT, accessToken(client.login(email, PASSWORD)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"FORBIDDEN\"");
    }

    @Test
    void promotionAppliesAtTheNextRefreshButShowsInMeImmediately() throws Exception {
        ResponseEntity<String> login = client.login(email, PASSWORD);
        String oldToken = accessToken(login);

        grant(Role.ADMIN);

        // /me reads the database: the new role is visible at once.
        assertThat(client.get("/api/v1/auth/me", oldToken).getBody()).contains("\"ADMIN\"").contains("\"role:read\"");
        // The old token still carries the old authorities (ADR-010 trade-off).
        assertThat(client.get(ADMIN_ENDPOINT, oldToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String newToken = accessToken(client.refresh(refreshCookieValue(login)));

        ResponseEntity<String> allowed = client.get(ADMIN_ENDPOINT, newToken);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getBody()).contains("role:read").contains("role:write").contains("role:assign");
        assertThat(claims(newToken).get("roles").toString()).isEqualTo("[\"ADMIN\",\"CUSTOMER\"]");
    }

    @Test
    void authoritiesAreTheUnionOfAllRoles() {
        grant(Role.ADMIN);

        UserAuthorities authorities = authorization.authoritiesOf(userId());

        assertThat(authorities.roles()).containsExactly("ADMIN", "CUSTOMER");
        assertThat(authorities.permissions()).containsExactlyElementsOf(
                java.util.Arrays.stream(Permission.values()).map(Permission::code).sorted().toList());
    }

    @Test
    void unknownUserHasNoAuthorities() {
        assertThat(authorization.authoritiesOf(UUID.randomUUID())).isEqualTo(UserAuthorities.none());
    }

    @Test
    void roleEntityLoadsItsPermissionsAsEnums() {
        var permissions = tx.execute(status -> roles.findByCode(Role.ADMIN).orElseThrow().getPermissions());

        assertThat(permissions).containsExactlyInAnyOrder(Permission.values());
    }
}
