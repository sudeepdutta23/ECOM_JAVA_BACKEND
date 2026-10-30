package com.ecommerce.backend.auth;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieHeader;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

/** Refresh rotation, theft detection, logout and logout-all over real HTTP. */
class SessionIT extends AbstractIntegrationTest {

    private static final String PROTECTED = "/api/v1/orders";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    private AuthTestClient client;
    private String email;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        email = "user-" + UUID.randomUUID() + "@session.test";
        client.registerAndVerify(email);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%@session.test'");
    }

    private String loginCookie() {
        return refreshCookieValue(client.login(email, PASSWORD));
    }

    private String revokeReason(String rawToken) {
        return jdbc.queryForObject("SELECT revoke_reason FROM refresh_tokens WHERE token_hash = ?", String.class,
                com.ecommerce.backend.auth.security.OpaqueTokens.hash(rawToken));
    }

    // ------------------------------------------------------------------ rotation

    @Test
    void refreshReturnsNewAccessTokenAndRotatesTheCookie() {
        String first = loginCookie();

        ResponseEntity<String> refreshed = client.refresh(first);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String second = refreshCookieValue(refreshed);
        assertThat(second).isNotEqualTo(first);
        assertThat(refreshCookieHeader(refreshed)).contains("HttpOnly").contains("SameSite=Strict");
        assertThat(client.get(PROTECTED, accessToken(refreshed)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(revokeReason(first)).isEqualTo("ROTATED");
        assertThat(revokeReason(second)).isNull();
    }

    @Test
    void aChainOfRefreshesKeepsWorking() {
        String cookie = loginCookie();

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> response = client.refresh(cookie);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            cookie = refreshCookieValue(response);
        }

        Integer families = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT family_id) FROM refresh_tokens t JOIN users u ON u.id = t.user_id
                WHERE u.email = ?""", Integer.class, email);
        assertThat(families).isEqualTo(1);
    }

    // ------------------------------------------------------------------ theft detection

    @Test
    void replayingAnOldTokenKillsTheWholeSessionAndAlertsTheOwner() {
        String stolen = loginCookie();
        String victimsCurrent = refreshCookieValue(client.refresh(stolen)); // victim rotates normally

        ResponseEntity<String> attacker = client.refresh(stolen);            // thief replays old copy

        assertThat(attacker.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(attacker.getBody()).contains("\"code\":\"AUTH_INVALID_REFRESH_TOKEN\"");
        // The victim's newer token is dead too: nobody keeps the session.
        assertThat(client.refresh(victimsCurrent).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revokeReason(victimsCurrent)).isEqualTo("REUSE_DETECTED");
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.sentTo(email).stream()
                .anyMatch(m -> m.subject().contains("signed you out")));
    }

    @Test
    void reuseKillsOnlyThatSessionNotOtherDevices() {
        String phone = loginCookie();
        String laptop = loginCookie();
        client.refresh(phone);

        client.refresh(phone); // replay on the phone family

        assertThat(client.refresh(laptop).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void concurrentRefreshWithOneTokenLetsExactlyOneSucceed() throws Exception {
        String cookie = loginCookie();
        int racers = 5;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> results = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return client.refresh(cookie);
            }));
        }

        start.countDown();
        int ok = 0;
        for (Future<ResponseEntity<String>> result : results) {
            if (result.get(30, TimeUnit.SECONDS).getStatusCode() == HttpStatus.OK) {
                ok++;
            }
        }
        pool.shutdown();

        // The row lock serializes the racers: one rotates, the rest see a reused token.
        assertThat(ok).isEqualTo(1);
    }

    // ------------------------------------------------------------------ rejection cases

    @Test
    void missingAndGarbageCookiesAreRejected() {
        assertThat(client.refresh(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.refresh("garbage").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.refresh("A".repeat(43)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        String cookie = loginCookie();
        jdbc.update("""
                UPDATE refresh_tokens SET issued_at = now() - interval '8 days', expires_at = now() - interval '1 day'
                WHERE token_hash = ?""", com.ecommerce.backend.auth.security.OpaqueTokens.hash(cookie));

        assertThat(client.refresh(cookie).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deactivatedUserCannotRefresh() {
        String cookie = loginCookie();
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE email = ?", email);

        assertThat(client.refresh(cookie).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revokeReason(cookie)).isEqualTo("ACCOUNT_DEACTIVATED");
    }

    // ------------------------------------------------------------------ logout

    @Test
    void logoutEndsTheSessionAndClearsTheCookie() {
        String cookie = loginCookie();

        ResponseEntity<String> response = client.logout(cookie);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refreshCookieHeader(response)).startsWith("refresh_token=;").contains("Max-Age=0");
        assertThat(client.refresh(cookie).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revokeReason(cookie)).isEqualTo("LOGOUT");
    }

    @Test
    void logoutIsIdempotentAndNeedsNoAccessToken() {
        String cookie = loginCookie();

        assertThat(client.logout(cookie).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.logout(cookie).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.logout(null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void logoutAllEndsEverySessionButNotOtherUsers() {
        ResponseEntity<String> phoneLogin = client.login(email, PASSWORD);
        String laptop = loginCookie();
        String otherEmail = "other-" + UUID.randomUUID() + "@session.test";
        client.registerAndVerify(otherEmail);
        String otherUsersSession = refreshCookieValue(client.login(otherEmail, PASSWORD));

        ResponseEntity<String> response = client.logoutAll(accessToken(phoneLogin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.refresh(refreshCookieValue(phoneLogin)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.refresh(laptop).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.refresh(otherUsersSession).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void accessTokenOutlivesLogoutAllUntilItExpires() {
        // Documents the stateless trade-off from ADR-003: no database check per request.
        String access = accessToken(client.login(email, PASSWORD));

        client.logoutAll(access);

        assertThat(client.get(PROTECTED, access).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void logoutAllRequiresAuthentication() {
        assertThat(client.logoutAll(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
