package com.ecommerce.backend.auth;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Erasure by the owner and by an administrator, over real HTTP, checked down to the rows. */
@ExtendWith(OutputCaptureExtension.class)
class AccountErasureIT extends AbstractIntegrationTest {

    private static final String USERS = "/api/v1/admin/users";
    private static final String DOMAIN = "@erasure.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TransactionTemplate tx;

    private AuthTestClient client;
    /** Erased rows no longer carry the test domain, so cleanup goes by ID. */
    private final List<UUID> created = new ArrayList<>();

    private record Account(UUID id, String email, String token) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
        jdbc.update("DELETE FROM roles WHERE code LIKE 'IT\\_ER\\_%'");
    }

    private Account account(String... roleCodes) {
        return accountWithEmail("e-" + UUID.randomUUID() + DOMAIN, roleCodes);
    }

    private Account accountWithEmail(String email, String... roleCodes) {
        client.registerAndVerify(email);
        for (String code : roleCodes) {
            jdbc.update("""
                    INSERT INTO user_roles (user_id, role_id)
                    SELECT u.id, r.id FROM users u, roles r WHERE u.email = ? AND r.code = ?
                    ON CONFLICT DO NOTHING""", email, code);
        }
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        return new Account(id, email, accessToken(client.login(email, PASSWORD)));
    }

    private ResponseEntity<String> eraseOwn(Account owner, String password) {
        return client.postJson("/api/v1/auth/me/erase", Map.of("password", password), owner.token());
    }

    private ResponseEntity<String> adminErase(Account caller, UUID target) {
        return client.send(HttpMethod.POST, USERS + "/" + target + "/erase", null, caller.token());
    }

    private long rows(String table, UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
    }

    private Map<String, Object> row(UUID userId) {
        return jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId);
    }

    // ------------------------------------------------------------------ by the owner

    @Test
    void theOwnerErasesTheirAccountAndNothingPersonalRemains(CapturedOutput logs) throws Exception {
        Account owner = account();
        String cookie = refreshCookieValue(client.login(owner.email(), PASSWORD));
        client.postJson("/api/v1/auth/password/forgot", Map.of("email", owner.email())); // a live reset link
        await().atMost(Duration.ofSeconds(10)).until(() -> rows("account_tokens", owner.id()) == 2);

        ResponseEntity<String> erased = eraseOwn(owner, PASSWORD);

        assertThat(erased.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> row = row(owner.id());
        assertThat(row.get("status")).isEqualTo("ERASED");
        assertThat(row.get("email")).isEqualTo("erased-" + owner.id() + "@erased.invalid");
        assertThat(row.get("password_hash")).isEqualTo("{erased}");
        assertThat(row.get("last_login_at")).isNull();
        assertThat(row.get("email_verified_at")).isNull();
        assertThat(row.get("status_changed_by")).isEqualTo(owner.id());
        assertThat(rows("refresh_tokens", owner.id())).isZero(); // deleted, not revoked: they hold IP and user agent
        assertThat(rows("account_tokens", owner.id())).isZero();
        assertThat(rows("user_roles", owner.id())).isZero();

        assertThat(client.refresh(cookie).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.get("/api/v1/auth/me", owner.token()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> oldLogin = client.login(owner.email(), PASSWORD);
        ResponseEntity<String> placeholderLogin = client.login("erased-" + owner.id() + "@erased.invalid", PASSWORD);
        assertThat(oldLogin.getBody()).contains("AUTH_INVALID_CREDENTIALS");
        assertThat(placeholderLogin.getBody()).contains("AUTH_INVALID_CREDENTIALS"); // and no 500 from '{erased}'

        await().atMost(Duration.ofSeconds(10)).until(() -> mail.sentTo(owner.email()).stream()
                .anyMatch(m -> m.subject().contains("erased")));
        assertThat(logs.getAll()).contains("Account erased by owner: userId=" + owner.id())
                .doesNotContain(owner.email());
    }

    @Test
    void theEmailCanBeRegisteredAgainAsANewAccount() {
        Account owner = account();
        eraseOwn(owner, PASSWORD);

        Account again = accountWithEmail(owner.email());

        assertThat(again.id()).isNotEqualTo(owner.id());
        assertThat(rows("user_roles", again.id())).isEqualTo(1); // a fresh CUSTOMER, nothing inherited
    }

    @Test
    void aWrongPasswordErasesNothing() {
        Account owner = account();

        ResponseEntity<String> response = eraseOwn(owner, "Wrong-Password-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("AUTH_INVALID_CURRENT_PASSWORD");
        assertThat(row(owner.id()).get("email")).isEqualTo(owner.email());
    }

    // ------------------------------------------------------------------ by an administrator

    @Test
    void anAdministratorErasesASuspendedAccountOnce() throws Exception {
        Account admin = account("ADMIN");
        Account customer = account();
        client.postJson(USERS + "/" + customer.id() + "/suspend", Map.of("reason", "Fraud"), admin.token());

        ResponseEntity<String> erased = adminErase(admin, customer.id());

        assertThat(erased.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(erased.getBody()).get("data");
        assertThat(data.get("status").asText()).isEqualTo("ERASED");
        assertThat(data.has("statusReason")).isFalse();
        assertThat(data.get("roles")).isEmpty();
        assertThat(data.get("statusChangedBy").asText()).isEqualTo(admin.id().toString());

        ResponseEntity<String> again = adminErase(admin, customer.id());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("USER_INVALID_STATUS");
        // The row is still there for the records, but it can never hold a role again.
        assertThat(client.get(USERS + "/" + customer.id(), admin.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID customerRole = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'CUSTOMER'", UUID.class);
        assertThat(client.send(HttpMethod.PUT, USERS + "/" + customer.id() + "/roles/" + customerRole, null,
                admin.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void erasureNeedsItsOwnPermission() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO roles (id, code, name) VALUES (?, 'IT_ER_SUPPORT', 'Support')", id);
        jdbc.update("INSERT INTO role_permissions (role_id, permission_code) VALUES (?, 'user:read'), (?, 'user:manage')",
                id, id);
        Account support = account("IT_ER_SUPPORT");
        Account customer = account();

        ResponseEntity<String> response = adminErase(support, customer.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(row(customer.id()).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void theLastActiveAdministratorCannotEraseThemselves() {
        Account admin = account("ADMIN");
        // The rule counts every admin in the database; start from exactly one. The container is throwaway.
        jdbc.update("DELETE FROM user_roles WHERE role_id = (SELECT id FROM roles WHERE code = 'ADMIN') AND user_id <> ?",
                admin.id());

        ResponseEntity<String> response = eraseOwn(admin, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("RBAC_LAST_ADMIN");
        assertThat(row(admin.id()).get("status")).isEqualTo("ACTIVE");
        assertThat(rows("user_roles", admin.id())).isEqualTo(2);
    }

    // ------------------------------------------------------------------ schema

    @Test
    void theDatabaseRefusesAnErasedRowThatStillHoldsPersonalData() {
        Account owner = account();

        assertThatThrownBy(() -> jdbc.update("UPDATE users SET status = 'ERASED' WHERE id = ?", owner.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_erased_is_anonymous");
    }

    // ------------------------------------------------------------------ Phase 3 review findings

    @Test
    void nobodyCanClaimSomeoneElsesErasurePlaceholder() {
        Account victim = account();
        String placeholder = "erased-" + victim.id() + "@erased.invalid";

        ResponseEntity<String> squat = client.postJson("/api/v1/auth/register",
                Map.of("email", placeholder, "password", PASSWORD));

        assertThat(squat.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(squat.getBody()).contains("VALIDATION_FAILED").contains("email");
        // Even past the code, the database keeps the domain for erased rows only (V6).
        Account other = account();
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET email = ? WHERE id = ?", placeholder, other.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_erased_domain_reserved");
        assertThat(eraseOwn(victim, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * A refresh in progress holds a share lock on the account row (the same statement as
     * {@code RefreshTokenRepository.lockOwnerStatusByTokenHash}) while it inserts the successor token.
     * Erasure must wait for it and then delete that token too; without the lock, the successor
     * would commit after the erasure's DELETE and keep an IP address and user agent forever.
     */
    @Test
    void anErasureWaitsForARefreshInProgressAndDeletesItsNewToken() throws Exception {
        Account admin = account("ADMIN");
        Account customer = account();
        CountDownLatch refreshHoldsTheLock = new CountDownLatch(1);
        CountDownLatch finishRefresh = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> refresh = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT status FROM users WHERE id = ? FOR SHARE", String.class, customer.id());
                jdbc.update("""
                        INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, issued_at, expires_at, created_ip, user_agent)
                        VALUES (gen_random_uuid(), ?, gen_random_uuid(), ?, now(), now() + interval '7 days',
                                '203.0.113.9', 'RaceBrowser/1.0')""", customer.id(), "b".repeat(64));
                refreshHoldsTheLock.countDown();
                awaitQuietly(finishRefresh);
            }));
            assertThat(refreshHoldsTheLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<String>> erase = pool.submit(() -> adminErase(admin, customer.id()));

            assertThatThrownBy(() -> erase.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            finishRefresh.countDown();
            refresh.get(10, TimeUnit.SECONDS);
            assertThat(erase.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(rows("refresh_tokens", customer.id())).isZero();
        } finally {
            finishRefresh.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
