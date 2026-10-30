package com.ecommerce.backend.auth;

import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.common.mail.EmailMessage;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import java.time.Duration;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One user's entire life cycle in a single readable test, and an audit of everything the
 * application logged meanwhile.
 *
 * <p>The second half is the important part: it proves, rather than promises, that no password,
 * token or full email address reaches the logs on any of these code paths.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthJourneyIT extends AbstractIntegrationTest {

    private static final String FIRST_PASSWORD = "Correct-Horse-9-Battery";
    private static final String SECOND_PASSWORD = "Even-Better-Passphrase-42";
    private static final String THIRD_PASSWORD = "Third-Good-Passphrase-77";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%@journey.test'");
    }

    @Test
    void completeAccountLifeCycleLeaksNoSecretsIntoLogs(CapturedOutput logs) {
        AuthTestClient client = new AuthTestClient(rest, mail);
        String email = "journey-" + UUID.randomUUID() + "@journey.test";
        List<String> secrets = new ArrayList<>(List.of(FIRST_PASSWORD, SECOND_PASSWORD, THIRD_PASSWORD, email));

        // 1. Register and verify
        client.register(email);
        String verificationToken = RecordingEmailSender.tokenFrom(awaitEmail(email, "Confirm your email"));
        secrets.add(verificationToken);
        assertThat(client.postJson("/api/v1/auth/email/verify", Map.of("token", verificationToken))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2. Login, read the profile, refresh
        ResponseEntity<String> login = client.login(email, FIRST_PASSWORD);
        String access = accessToken(login);
        String refresh = refreshCookieValue(login);
        secrets.addAll(List.of(access, refresh));
        assertThat(client.get("/api/v1/auth/me", access).getBody()).contains("\"status\":\"ACTIVE\"");
        ResponseEntity<String> refreshed = client.refresh(refresh);
        secrets.addAll(List.of(accessToken(refreshed), refreshCookieValue(refreshed)));

        // 3. A wrong password, then change the password
        assertThat(client.login(email, "Wrong-Password-123").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        secrets.add("Wrong-Password-123");
        ResponseEntity<String> changed = client.postJson("/api/v1/auth/password/change",
                Map.of("currentPassword", FIRST_PASSWORD, "newPassword", SECOND_PASSWORD), accessToken(refreshed));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        secrets.addAll(List.of(accessToken(changed), refreshCookieValue(changed)));

        // 4. Sign out everywhere, forget the password, reset it
        assertThat(client.logoutAll(accessToken(changed)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        client.postJson("/api/v1/auth/password/forgot", Map.of("email", email));
        String resetToken = RecordingEmailSender.tokenFrom(awaitEmail(email, "Reset your password"));
        secrets.add(resetToken);
        assertThat(client.postJson("/api/v1/auth/password/reset",
                Map.of("token", resetToken, "newPassword", THIRD_PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // 5. Sign in with the newest password and deactivate
        ResponseEntity<String> finalLogin = client.login(email, THIRD_PASSWORD);
        assertThat(finalLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        secrets.addAll(List.of(accessToken(finalLogin), refreshCookieValue(finalLogin)));
        assertThat(client.postJson("/api/v1/auth/me/deactivate", Map.of("password", THIRD_PASSWORD),
                accessToken(finalLogin)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.login(email, THIRD_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        awaitEmail(email, "deactivated");

        // 6. The log audit. First prove the capture works, then that nothing secret is in it.
        String output = logs.getAll();
        assertThat(output).contains("Login succeeded").contains("Password changed").contains("Account deactivated");
        for (String secret : secrets) {
            assertThat(output).as("logs must not contain a secret or the full email").doesNotContain(secret);
        }
        assertThat(output).doesNotContain("$argon2id$");
    }

    private EmailMessage awaitEmail(String email, String subjectPart) {
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.sentTo(email).stream()
                .anyMatch(m -> m.subject().contains(subjectPart)));
        return mail.sentTo(email).stream().filter(m -> m.subject().contains(subjectPart))
                .reduce((first, second) -> second).orElseThrow();
    }
}
