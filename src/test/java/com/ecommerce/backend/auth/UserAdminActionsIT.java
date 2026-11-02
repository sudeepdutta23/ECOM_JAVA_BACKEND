package com.ecommerce.backend.auth;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.auth.security.OpaqueTokens;
import com.ecommerce.backend.rbac.Permission;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
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

/** Suspend, reactivate, unlock and sign out over real HTTP, with their effect on the owner. */
@ExtendWith(OutputCaptureExtension.class)
class UserAdminActionsIT extends AbstractIntegrationTest {

    private static final String USERS = "/api/v1/admin/users";
    private static final String DOMAIN = "@actions.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private AuthTestClient client;
    private Account admin;

    private record Account(UUID id, String email, String token) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        admin = account("ADMIN");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
        jdbc.update("DELETE FROM roles WHERE code LIKE 'IT\\_UA\\_%'");
    }

    private Account account(String... roleCodes) {
        String email = "u-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        for (String code : roleCodes) {
            jdbc.update("""
                    INSERT INTO user_roles (user_id, role_id)
                    SELECT u.id, r.id FROM users u, roles r WHERE u.email = ? AND r.code = ?
                    ON CONFLICT DO NOTHING""", email, code);
        }
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        return new Account(id, email, accessToken(client.login(email, PASSWORD)));
    }

    private void customRole(String code, String... permissions) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO roles (id, code, name) VALUES (?, ?, ?)", id, code, code);
        for (String permission : permissions) {
            jdbc.update("INSERT INTO role_permissions (role_id, permission_code) VALUES (?, ?)", id, permission);
        }
    }

    private ResponseEntity<String> suspend(Account caller, UUID target, String reason) {
        return client.postJson(USERS + "/" + target + "/suspend", Map.of("reason", reason), caller.token());
    }

    private ResponseEntity<String> action(Account caller, UUID target, String action) {
        return client.send(HttpMethod.POST, USERS + "/" + target + "/" + action, null, caller.token()); // no body
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("data");
    }

    private String revokeReason(String refreshCookie) {
        return jdbc.queryForObject("SELECT revoke_reason FROM refresh_tokens WHERE token_hash = ?",
                String.class, OpaqueTokens.hash(refreshCookie));
    }

    // ------------------------------------------------------------------ suspend and reactivate

    @Test
    void suspensionEndsSessionsAndBlocksSignInUntilReactivated(CapturedOutput logs) throws Exception {
        Account customer = account();
        String cookie = refreshCookieValue(client.login(customer.email(), PASSWORD));
        String reason = "Chargeback ring " + UUID.randomUUID();

        ResponseEntity<String> suspended = suspend(admin, customer.id(), reason);

        assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(suspended).get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(data(suspended).get("statusReason").asText()).isEqualTo(reason);
        assertThat(data(suspended).get("statusChangedBy").asText()).isEqualTo(admin.id().toString());
        assertThat(data(suspended).get("activeSessions").asLong()).isZero();
        assertThat(client.refresh(cookie).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revokeReason(cookie)).isEqualTo("ACCOUNT_SUSPENDED");
        ResponseEntity<String> login = client.login(customer.email(), PASSWORD);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(login.getBody()).contains("suspended").doesNotContain(reason);

        ResponseEntity<String> reactivated = action(admin, customer.id(), "reactivate");

        assertThat(data(reactivated).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(data(reactivated).has("statusReason")).isFalse();
        assertThat(client.login(customer.email(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logs.getAll()).contains("Account suspended: userId=" + customer.id()).doesNotContain(reason);
    }

    @Test
    void aSelfDeactivatedAccountCanBeReactivatedByAnAdmin() throws Exception {
        Account customer = account();
        client.postJson("/api/v1/auth/me/deactivate", Map.of("password", PASSWORD), customer.token());

        assertThat(data(action(admin, customer.id(), "reactivate")).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(client.login(customer.email(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void transitionsMustFitTheCurrentStatus() {
        Account customer = account();
        suspend(admin, customer.id(), "First");

        ResponseEntity<String> again = suspend(admin, customer.id(), "Second");
        ResponseEntity<String> reactivateActive = action(admin, account().id(), "reactivate");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("USER_INVALID_STATUS").contains("SUSPENDED");
        assertThat(reactivateActive.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------ unlock and sign out

    @Test
    void unlockEndsALockoutEarly() throws Exception {
        Account customer = account();
        for (int i = 0; i < 5; i++) {
            client.login(customer.email(), "Wrong-Password-" + i);
        }
        assertThat(client.login(customer.email(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // locked

        ResponseEntity<String> unlocked = action(admin, customer.id(), "unlock");

        assertThat(data(unlocked).get("failedLoginCount").asInt()).isZero();
        assertThat(data(unlocked).has("lockedUntil")).isFalse();
        assertThat(client.login(customer.email(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void signOutEndsEverySessionButKeepsTheAccountActive() throws Exception {
        Account customer = account();
        String phone = refreshCookieValue(client.login(customer.email(), PASSWORD));
        String laptop = refreshCookieValue(client.login(customer.email(), PASSWORD));

        ResponseEntity<String> response = action(admin, customer.id(), "sign-out");

        assertThat(data(response).get("activeSessions").asLong()).isZero();
        assertThat(data(response).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(client.refresh(phone).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.refresh(laptop).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revokeReason(phone)).isEqualTo("ADMIN_SIGN_OUT");
        assertThat(client.login(customer.email(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ rules

    @Test
    void aSupportAgentCanManageCustomersButNotAdministrators() {
        customRole("IT_UA_SUPPORT", "user:read", "user:manage");
        Account agent = account("IT_UA_SUPPORT");
        Account customer = account();

        ResponseEntity<String> onCustomer = suspend(agent, customer.id(), "Abuse");
        ResponseEntity<String> onAdmin = suspend(agent, admin.id(), "Takeover");
        ResponseEntity<String> signOutAdmin = action(agent, admin.id(), "sign-out");

        assertThat(onCustomer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(onAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(onAdmin.getBody()).contains("RBAC_ESCALATION_DENIED");
        assertThat(signOutAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(client.get("/api/v1/auth/me", admin.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void noActionWorksOnYourOwnAccount() {
        ResponseEntity<String> response = suspend(admin, admin.id(), "Testing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("USER_SELF_ACTION_DENIED");
    }

    @Test
    void theLastActiveAdministratorCannotBeSuspended() {
        // The rule counts every admin in the database; start from exactly one. The container is throwaway.
        jdbc.update("DELETE FROM user_roles WHERE role_id = (SELECT id FROM roles WHERE code = 'ADMIN') AND user_id <> ?",
                admin.id());
        customRole("IT_UA_SUPER", Arrays.stream(Permission.values()).map(Permission::code).toArray(String[]::new));
        Account superUser = account("IT_UA_SUPER"); // holds every permission, but not the ADMIN role

        ResponseEntity<String> response = suspend(superUser, admin.id(), "Takeover");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("RBAC_LAST_ADMIN");
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, admin.id())).isEqualTo("ACTIVE");
    }

    @Test
    void customersCannotUseTheActions() {
        Account customer = account();
        Account other = account();

        assertThat(action(customer, other.id(), "sign-out").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
