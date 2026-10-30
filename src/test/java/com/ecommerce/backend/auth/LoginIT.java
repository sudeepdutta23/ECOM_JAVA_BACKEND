package com.ecommerce.backend.auth;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.auth.security.OpaqueTokens;
import com.ecommerce.backend.common.mail.EmailMessage;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Login, lockout and token issuing against the running application and real PostgreSQL. */
class LoginIT extends AbstractIntegrationTest {

    private static final String WRONG = "Wrong-Password-123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AuthTestClient client;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%@login.test'");
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@login.test";
    }

    private Integer failedCount(String email) {
        return jdbc.queryForObject("SELECT failed_login_count FROM users WHERE email = ?", Integer.class, email);
    }

    // ------------------------------------------------------------------ success

    @Test
    void successfulLoginReturnsUsableAccessTokenAndSecureRefreshCookie() {
        String email = uniqueEmail();
        client.registerAndVerify(email);

        ResponseEntity<String> response = client.login(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"tokenType\":\"Bearer\"").contains("\"expiresIn\":900")
                .doesNotContain("refreshToken");
        assertThat(AuthTestClient.refreshCookieHeader(response))
                .contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth").contains("Max-Age=604800");

        // The access token gets past security (404 = routing ran, nothing mapped there yet).
        assertThat(client.get("/api/v1/orders", AuthTestClient.accessToken(response)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refreshTokenIsStoredHashedWithDeviceInformation() {
        String email = uniqueEmail();
        client.registerAndVerify(email);

        String cookieValue = AuthTestClient.refreshCookieValue(client.login(email, PASSWORD));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT t.token_hash, t.family_id, t.user_agent, t.revoked_at
                FROM refresh_tokens t JOIN users u ON u.id = t.user_id WHERE u.email = ?""", email);
        assertThat(row.get("token_hash")).isEqualTo(OpaqueTokens.hash(cookieValue));
        assertThat(row.get("family_id")).isNotNull();
        assertThat(row.get("user_agent")).isEqualTo("AuthTestClient/1.0");
        assertThat(row.get("revoked_at")).isNull();
        Integer rawRows = jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ?",
                Integer.class, cookieValue);
        assertThat(rawRows).isZero();
    }

    @Test
    void emailIsCaseInsensitiveAtLogin() {
        String email = uniqueEmail();
        client.registerAndVerify(email);

        assertThat(client.login("  " + email.toUpperCase() + " ", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void eachLoginCreatesAnIndependentSession() {
        String email = uniqueEmail();
        client.registerAndVerify(email);

        client.login(email, PASSWORD);
        client.login(email, PASSWORD);

        Integer families = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT t.family_id) FROM refresh_tokens t JOIN users u ON u.id = t.user_id
                WHERE u.email = ?""", Integer.class, email);
        assertThat(families).isEqualTo(2);
    }

    // ------------------------------------------------------------------ failures look identical

    @Test
    void unknownEmailAndWrongPasswordAreIndistinguishable() {
        String email = uniqueEmail();
        client.registerAndVerify(email);

        ResponseEntity<String> wrongPassword = client.login(email, WRONG);
        ResponseEntity<String> unknownEmail = client.login(uniqueEmail(), WRONG);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(AuthTestClient.stableBody(wrongPassword)).isEqualTo(AuthTestClient.stableBody(unknownEmail));
        assertThat(wrongPassword.getHeaders().get("Set-Cookie")).isNull();
    }

    @Test
    void successResetsTheFailureCounter() {
        String email = uniqueEmail();
        client.registerAndVerify(email);
        client.login(email, WRONG);
        client.login(email, WRONG);
        assertThat(failedCount(email)).isEqualTo(2);

        client.login(email, PASSWORD);

        assertThat(failedCount(email)).isZero();
    }

    // ------------------------------------------------------------------ lockout

    @Test
    void fiveFailuresLockTheAccountEvenForTheCorrectPassword() {
        String email = uniqueEmail();
        client.registerAndVerify(email);

        for (int i = 0; i < 5; i++) {
            client.login(email, WRONG);
        }
        ResponseEntity<String> correctButLocked = client.login(email, PASSWORD);
        ResponseEntity<String> wrong = client.login(uniqueEmail(), WRONG);

        assertThat(correctButLocked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(AuthTestClient.stableBody(correctButLocked)).isEqualTo(AuthTestClient.stableBody(wrong));
        assertThat(failedCount(email)).isEqualTo(5); // locked attempts are not counted further
    }

    @Test
    void ownerIsEmailedOnceWhenTheAccountLocks() {
        String email = uniqueEmail();
        client.registerAndVerify(email);

        for (int i = 0; i < 7; i++) {
            client.login(email, WRONG);
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> lockEmails(email).size() == 1);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> lockEmails(email).size() == 1);
        assertThat(lockEmails(email).get(0).body()).contains("unlocks automatically");
    }

    @Test
    void lockExpiresOnItsOwn() {
        String email = uniqueEmail();
        client.registerAndVerify(email);
        for (int i = 0; i < 5; i++) {
            client.login(email, WRONG);
        }

        // Simulate the 15 minutes passing.
        jdbc.update("UPDATE users SET locked_until = now() - interval '1 second' WHERE email = ?", email);

        assertThat(client.login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failedCount(email)).isZero();
    }

    @Test
    void parallelGuessingStillEndsLocked() throws Exception {
        String email = uniqueEmail();
        client.registerAndVerify(email);
        int attackers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> attempts = new ArrayList<>();
        for (int i = 0; i < attackers; i++) {
            attempts.add(pool.submit(() -> {
                start.await();
                return client.login(email, WRONG);
            }));
        }

        start.countDown();
        for (Future<ResponseEntity<String>> attempt : attempts) {
            assertThat(attempt.get(60, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        pool.shutdown();

        assertThat(failedCount(email)).isGreaterThanOrEqualTo(5);
        assertThat(client.login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ account state

    @Test
    void unverifiedAccountIsToldToVerify() {
        String email = uniqueEmail();
        client.register(email);

        ResponseEntity<String> response = client.login(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"AUTH_EMAIL_NOT_VERIFIED\"");
    }

    @Test
    void deactivatedAccountCannotLogIn() {
        String email = uniqueEmail();
        client.registerAndVerify(email);
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE email = ?", email);

        ResponseEntity<String> response = client.login(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"code\":\"AUTH_ACCOUNT_DISABLED\"");
    }

    @Test
    void legacyBcryptHashIsUpgradedToArgon2OnLogin() {
        String email = uniqueEmail();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, status, email_verified_at, password_changed_at)
                VALUES (?, ?, ?, 'ACTIVE', now(), now())""",
                UUID.randomUUID(), email, "{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD));

        assertThat(client.login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).startsWith("{argon2}");
        assertThat(client.login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK); // new hash works
    }

    private List<EmailMessage> lockEmails(String email) {
        return mail.sentTo(email).stream().filter(m -> m.subject().contains("locked")).toList();
    }
}
