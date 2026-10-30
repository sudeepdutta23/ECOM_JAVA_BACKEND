package com.ecommerce.backend.auth;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieHeader;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.common.mail.EmailMessage;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Password change, forgot/reset, /me and self-deactivation over real HTTP. */
class AccountLifecycleIT extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "Even-Better-Passphrase-42";

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
        email = "user-" + UUID.randomUUID() + "@account.test";
        client.registerAndVerify(email);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%@account.test'");
    }

    private EmailMessage awaitEmail(String subjectPart) {
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.sentTo(email).stream()
                .anyMatch(m -> m.subject().contains(subjectPart)));
        return mail.sentTo(email).stream().filter(m -> m.subject().contains(subjectPart))
                .reduce((first, second) -> second).orElseThrow();
    }

    private ResponseEntity<String> changePassword(String accessToken, String current, String next) {
        return client.postJson("/api/v1/auth/password/change",
                Map.of("currentPassword", current, "newPassword", next), accessToken);
    }

    // ------------------------------------------------------------------ change

    @Test
    void changePasswordEndsOtherSessionsButKeepsThisDeviceSignedIn() {
        ResponseEntity<String> laptopLogin = client.login(email, PASSWORD);
        String phoneSession = refreshCookieValue(client.login(email, PASSWORD));

        ResponseEntity<String> changed = changePassword(accessToken(laptopLogin), PASSWORD, NEW_PASSWORD);

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(client.refresh(phoneSession).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.refresh(refreshCookieValue(laptopLogin)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.refresh(refreshCookieValue(changed)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(client.login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(awaitEmail("password was changed").body()).contains("signed out");
    }

    @Test
    void wrongCurrentPasswordCountsTowardLockout() {
        String access = accessToken(client.login(email, PASSWORD));

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> response = changePassword(access, "Not-My-Password-1", NEW_PASSWORD);
            assertThat(response.getBody()).contains("\"code\":\"AUTH_INVALID_CURRENT_PASSWORD\"");
        }

        // A stolen access token cannot be used to brute-force the password: the account is now locked.
        assertThat(client.login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        awaitEmail("temporarily locked");
    }

    @Test
    void changeRejectsReusingTheCurrentPassword() {
        String access = accessToken(client.login(email, PASSWORD));

        ResponseEntity<String> response = changePassword(access, PASSWORD, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("must be different");
    }

    // ------------------------------------------------------------------ forgot / reset

    @Test
    void forgotAndResetPasswordFlow() {
        String oldSession = refreshCookieValue(client.login(email, PASSWORD));

        ResponseEntity<String> forgot = client.postJson("/api/v1/auth/password/forgot", Map.of("email", email));
        assertThat(forgot.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        EmailMessage resetEmail = awaitEmail("Reset your password");
        assertThat(resetEmail.body()).contains("/reset-password?token=").contains("30 minutes");

        ResponseEntity<String> reset = client.postJson("/api/v1/auth/password/reset",
                Map.of("token", RecordingEmailSender.tokenFrom(resetEmail), "newPassword", NEW_PASSWORD));

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.refresh(oldSession).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitEmail("password was changed");
    }

    @Test
    void resetLinkWorksOnlyOnce() {
        client.postJson("/api/v1/auth/password/forgot", Map.of("email", email));
        String token = RecordingEmailSender.tokenFrom(awaitEmail("Reset your password"));
        client.postJson("/api/v1/auth/password/reset", Map.of("token", token, "newPassword", NEW_PASSWORD));

        ResponseEntity<String> again = client.postJson("/api/v1/auth/password/reset",
                Map.of("token", token, "newPassword", "Another-Long-Passphrase-7"));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(again.getBody()).contains("\"code\":\"AUTH_INVALID_ACCOUNT_TOKEN\"");
    }

    @Test
    void weakPasswordOnResetKeepsTheLinkUsable() {
        client.postJson("/api/v1/auth/password/forgot", Map.of("email", email));
        String token = RecordingEmailSender.tokenFrom(awaitEmail("Reset your password"));

        ResponseEntity<String> weak = client.postJson("/api/v1/auth/password/reset",
                Map.of("token", token, "newPassword", "Password1234"));
        ResponseEntity<String> strong = client.postJson("/api/v1/auth/password/reset",
                Map.of("token", token, "newPassword", NEW_PASSWORD));

        assertThat(weak.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(strong.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void expiredResetLinkIsRejected() {
        client.postJson("/api/v1/auth/password/forgot", Map.of("email", email));
        String token = RecordingEmailSender.tokenFrom(awaitEmail("Reset your password"));
        jdbc.update("""
                UPDATE account_tokens SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'
                WHERE token_hash = ?""", com.ecommerce.backend.auth.security.OpaqueTokens.hash(token));

        ResponseEntity<String> response = client.postJson("/api/v1/auth/password/reset",
                Map.of("token", token, "newPassword", NEW_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resetUnlocksALockedAccount() {
        for (int i = 0; i < 5; i++) {
            client.login(email, "Wrong-Password-123");
        }
        client.postJson("/api/v1/auth/password/forgot", Map.of("email", email));
        String token = RecordingEmailSender.tokenFrom(awaitEmail("Reset your password"));

        client.postJson("/api/v1/auth/password/reset", Map.of("token", token, "newPassword", NEW_PASSWORD));

        assertThat(client.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void forgotForUnknownEmailLooksIdentical() {
        ResponseEntity<String> known = client.postJson("/api/v1/auth/password/forgot", Map.of("email", email));
        ResponseEntity<String> unknown = client.postJson("/api/v1/auth/password/forgot",
                Map.of("email", "nobody-" + UUID.randomUUID() + "@account.test"));

        assertThat(AuthTestClient.stableBody(unknown)).isEqualTo(AuthTestClient.stableBody(known));
    }

    // ------------------------------------------------------------------ me

    @Test
    void meReturnsTheSignedInAccountWithoutSecrets() {
        String access = accessToken(client.login(email, PASSWORD));

        ResponseEntity<String> me = client.get("/api/v1/auth/me", access);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody())
                .contains("\"email\":\"" + email + "\"")
                .contains("\"status\":\"ACTIVE\"")
                .contains("\"emailVerified\":true")
                .contains("\"lastLoginAt\"")
                .doesNotContain("password")
                .doesNotContain("argon2");
    }

    @Test
    void meRequiresAToken() {
        assertThat(client.get("/api/v1/auth/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ deactivate

    @Test
    void deactivateEndsEverythingAndBlocksLogin() {
        ResponseEntity<String> login = client.login(email, PASSWORD);
        String access = accessToken(login);

        ResponseEntity<String> response = client.postJson("/api/v1/auth/me/deactivate", Map.of("password", PASSWORD), access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refreshCookieHeader(response)).contains("Max-Age=0");
        assertThat(client.refresh(refreshCookieValue(login)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> relogin = client.login(email, PASSWORD);
        assertThat(relogin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(relogin.getBody()).contains("AUTH_ACCOUNT_DISABLED");
        awaitEmail("deactivated");
    }

    @Test
    void deactivateWithWrongPasswordChangesNothing() {
        String access = accessToken(client.login(email, PASSWORD));

        ResponseEntity<String> response = client.postJson("/api/v1/auth/me/deactivate",
                Map.of("password", "Not-My-Password-1"), access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email))
                .isEqualTo("ACTIVE");
    }
}
