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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Granting and revoking over real HTTP, including the last-admin race and the bootstrap. */
class RoleAssignmentIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@assign.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoleAssignmentService assignments;

    @Autowired
    private TransactionTemplate transactions;

    private AuthTestClient client;

    /** A signed-in test user. */
    private record Account(UUID id, String email, String token) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");   // cascades user_roles
        jdbc.update("DELETE FROM roles WHERE code LIKE 'IT\\_%' AND NOT system_role");
    }

    private Account account(String... roleCodes) {
        String email = "user-" + UUID.randomUUID() + DOMAIN;
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

    private UUID role(String code) {
        return jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, code);
    }

    private UUID customRole(String code, String... permissions) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO roles (id, code, name, system_role) VALUES (?, ?, ?, false)", id, code, code);
        for (String permission : permissions) {
            jdbc.update("INSERT INTO role_permissions (role_id, permission_code) VALUES (?, ?)", id, permission);
        }
        return id;
    }

    private static String path(UUID userId, UUID roleId) {
        return "/api/v1/admin/users/" + userId + "/roles/" + roleId;
    }

    private ResponseEntity<String> grant(Account caller, UUID userId, UUID roleId) {
        return client.send(HttpMethod.PUT, path(userId, roleId), null, caller.token());
    }

    private ResponseEntity<String> revoke(Account caller, UUID userId, UUID roleId) {
        return client.send(HttpMethod.DELETE, path(userId, roleId), null, caller.token());
    }

    private boolean holds(UUID userId, String roleCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                               WHERE ur.user_id = ? AND r.code = ?)""", Boolean.class, userId, roleCode));
    }

    /**
     * The last-admin rule counts every admin in the database, and other test classes leave theirs
     * behind. The tests that need "exactly these admins" start from none. The container is throwaway.
     */
    private void removeAllAdmins() {
        jdbc.update("DELETE FROM user_roles WHERE role_id = (SELECT id FROM roles WHERE code = 'ADMIN')");
    }

    // ------------------------------------------------------------------ grant and revoke

    @Test
    void adminGrantsAndRevokesAndTheHolderSeesItAfterRefresh() {
        Account admin = account("ADMIN");
        String email = "member-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        ResponseEntity<String> login = client.login(email, PASSWORD);
        UUID memberId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        UUID viewer = customRole("IT_VIEWER", "role:read");

        assertThat(grant(admin, memberId, viewer).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(grant(admin, memberId, viewer).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); // idempotent

        ResponseEntity<String> list = client.get("/api/v1/admin/users/" + memberId + "/roles", admin.token());
        assertThat(list.getBody()).contains("\"CUSTOMER\"").contains("\"IT_VIEWER\"")
                .contains("\"assignedBy\":\"" + admin.id() + "\"");

        String refreshed = accessToken(client.refresh(refreshCookieValue(login)));
        assertThat(client.get("/api/v1/admin/roles", refreshed).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(revoke(admin, memberId, viewer).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(revoke(admin, memberId, viewer).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); // idempotent
        assertThat(holds(memberId, "IT_VIEWER")).isFalse();
    }

    @Test
    void nobodyChangesTheirOwnRoles() {
        Account admin = account("ADMIN");

        ResponseEntity<String> response = grant(admin, admin.id(), customRole("IT_SELF", "role:read"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("RBAC_SELF_MODIFICATION_DENIED");
    }

    @Test
    void customerIsNeverRevokedAndUnknownIdsAre404() {
        Account admin = account("ADMIN");
        Account member = account();

        ResponseEntity<String> customer = revoke(admin, member.id(), role("CUSTOMER"));
        ResponseEntity<String> noUser = grant(admin, UUID.randomUUID(), role("CUSTOMER"));
        ResponseEntity<String> noRole = grant(admin, member.id(), UUID.randomUUID());

        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(customer.getBody()).contains("RBAC_SYSTEM_ROLE");
        assertThat(noUser.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(noRole.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(holds(member.id(), "CUSTOMER")).isTrue();
    }

    @Test
    void anAssignerCanOnlyHandOutRolesWithinTheirOwnPower() {
        customRole("IT_ASSIGNER", "role:read", "role:assign");
        Account assigner = account("IT_ASSIGNER");
        Account member = account();
        Account admin = account("ADMIN");

        ResponseEntity<String> promote = grant(assigner, member.id(), role("ADMIN"));
        ResponseEntity<String> demote = revoke(assigner, admin.id(), role("ADMIN"));
        ResponseEntity<String> fine = grant(assigner, member.id(), customRole("IT_READER", "role:read"));

        assertThat(promote.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(promote.getBody()).contains("RBAC_ESCALATION_DENIED").contains("role:write");
        assertThat(demote.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fine.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(holds(member.id(), "ADMIN")).isFalse();
        assertThat(holds(admin.id(), "ADMIN")).isTrue();
    }

    @Test
    void customerCannotSeeOrChangeAssignments() {
        Account customer = account();
        Account other = account();

        assertThat(client.get("/api/v1/admin/users/" + other.id() + "/roles", customer.token()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(grant(customer, other.id(), role("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ last admin

    @Test
    void theLastActiveAdminCannotBeDemoted() {
        removeAllAdmins();
        Account onlyAdmin = account("ADMIN");
        // A non-admin who holds every permission (so the escalation rule lets them try).
        customRole("IT_SUPER", Arrays.stream(Permission.values()).map(Permission::code).toArray(String[]::new));
        Account superUser = account("IT_SUPER");

        ResponseEntity<String> response = revoke(superUser, onlyAdmin.id(), role("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("RBAC_LAST_ADMIN");
        assertThat(holds(onlyAdmin.id(), "ADMIN")).isTrue();
    }

    @Test
    void twoAdminsDemotingEachOtherAtOnceLeaveOneAdmin() throws Exception {
        removeAllAdmins();
        Account alice = account("ADMIN");
        Account bob = account("ADMIN");
        UUID adminRole = role("ADMIN");
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<ResponseEntity<String>> aliceRevokesBob = CompletableFuture.supplyAsync(() -> {
            await(start);
            return revoke(alice, bob.id(), adminRole);
        });
        CompletableFuture<ResponseEntity<String>> bobRevokesAlice = CompletableFuture.supplyAsync(() -> {
            await(start);
            return revoke(bob, alice.id(), adminRole);
        });
        start.countDown();
        List<HttpStatus> statuses = List.of(aliceRevokesBob.get(), bobRevokesAlice.get()).stream()
                .map(r -> HttpStatus.valueOf(r.getStatusCode().value())).toList();

        // The winner demotes the other. The loser runs after it (the ADMIN row lock) and is refused:
        // either it is no longer an admin itself (403) or it would remove the last one (409).
        assertThat(statuses).containsOnlyOnce(HttpStatus.NO_CONTENT);
        assertThat(statuses).containsAnyOf(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE r.code = 'ADMIN' AND ur.user_id IN (?, ?)""", Integer.class, alice.id(), bob.id())).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ bootstrap

    @Test
    void bootstrapPromotesTheConfiguredAccountOnlyWhileNoAdminExists() {
        Account first = account();
        Account second = account();

        // Inside one transaction that is rolled back, so the admins of other tests are untouched.
        transactions.executeWithoutResult(tx -> {
            removeAllAdmins();

            assertThat(assignments.bootstrapAdmin("nobody-" + UUID.randomUUID() + DOMAIN))
                    .isEqualTo(BootstrapOutcome.NO_ACTIVE_ACCOUNT);
            assertThat(assignments.bootstrapAdmin(first.email().toUpperCase())) // normalized like login
                    .isEqualTo(BootstrapOutcome.GRANTED);
            assertThat(assignments.bootstrapAdmin(second.email()))
                    .isEqualTo(BootstrapOutcome.ADMIN_ALREADY_EXISTS);

            assertThat(holds(first.id(), "ADMIN")).isTrue();
            assertThat(holds(second.id(), "ADMIN")).isFalse();
            assertThat(jdbc.queryForObject("""
                    SELECT assigned_by IS NULL FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                    WHERE ur.user_id = ? AND r.code = 'ADMIN'""", Boolean.class, first.id())).isTrue();
            tx.setRollbackOnly();
        });
    }

    @Test
    void bootstrapIgnoresAnUnverifiedAccount() {
        String pending = "pending-" + UUID.randomUUID() + DOMAIN;
        client.register(pending);

        transactions.executeWithoutResult(tx -> {
            removeAllAdmins();
            assertThat(assignments.bootstrapAdmin(pending)).isEqualTo(BootstrapOutcome.NO_ACTIVE_ACCOUNT);
            tx.setRollbackOnly();
        });
    }
}
