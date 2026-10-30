package com.ecommerce.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.common.mail.EmailMessage;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The whole registration journey over real HTTP, real PostgreSQL and the async email path. */
class RegistrationIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Correct-Horse-9-Battery";
    private static final Duration EMAIL_WAIT = Duration.ofSeconds(10);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingEmailSender mail;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%@reg.test'");
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@reg.test";
    }

    private ResponseEntity<String> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> register(String email) {
        return post("/api/v1/auth/register", Map.of("email", email, "password", PASSWORD));
    }

    private List<EmailMessage> awaitEmails(String email, int count) {
        await().atMost(EMAIL_WAIT).until(() -> mail.sentTo(email).size() >= count);
        return mail.sentTo(email);
    }

    private String status(String email) {
        return jdbc.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email);
    }

    @Test
    void registerThenVerifyActivatesTheAccount() {
        String email = uniqueEmail();

        ResponseEntity<String> registered = register(email);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(status(email)).isEqualTo("PENDING_VERIFICATION");

        EmailMessage message = awaitEmails(email, 1).get(0);
        assertThat(message.subject()).isEqualTo("Confirm your email address");
        assertThat(message.body()).contains("http://localhost:3000/verify-email?token=");

        ResponseEntity<String> verified = post("/api/v1/auth/email/verify",
                Map.of("token", RecordingEmailSender.tokenFrom(message)));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(email)).isEqualTo("ACTIVE");
    }

    @Test
    void secretsAreStoredOnlyAsHashes() {
        String email = uniqueEmail();
        register(email);
        String rawToken = RecordingEmailSender.tokenFrom(awaitEmails(email, 1).get(0));

        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        Integer rawTokenRows = jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens WHERE token_hash = ?",
                Integer.class, rawToken);

        assertThat(passwordHash).startsWith("{argon2}$argon2id$").doesNotContain(PASSWORD);
        assertThat(rawTokenRows).isZero();
    }

    @Test
    void verificationLinkWorksOnlyOnce() {
        String email = uniqueEmail();
        register(email);
        String token = RecordingEmailSender.tokenFrom(awaitEmails(email, 1).get(0));
        post("/api/v1/auth/email/verify", Map.of("token", token));

        ResponseEntity<String> again = post("/api/v1/auth/email/verify", Map.of("token", token));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(again.getBody()).contains("\"code\":\"AUTH_INVALID_ACCOUNT_TOKEN\"");
    }

    @Test
    void unknownTokenIsRejected() {
        ResponseEntity<String> response = post("/api/v1/auth/email/verify",
                Map.of("token", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"AUTH_INVALID_ACCOUNT_TOKEN\"");
    }

    @Test
    void registeringAnExistingAccountLooksIdenticalButNotifiesTheOwner() {
        String email = uniqueEmail();
        register(email);
        post("/api/v1/auth/email/verify", Map.of("token", RecordingEmailSender.tokenFrom(awaitEmails(email, 1).get(0))));

        ResponseEntity<String> first = register(uniqueEmail());
        ResponseEntity<String> duplicate = register(email);

        assertThat(duplicate.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(body(duplicate)).isEqualTo(body(first));
        List<EmailMessage> emails = awaitEmails(email, 2);
        assertThat(emails.get(1).subject()).isEqualTo("Someone tried to register with your email");
        Integer accounts = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(accounts).isEqualTo(1);
    }

    @Test
    void resendInvalidatesTheEarlierLink() {
        String email = uniqueEmail();
        register(email);
        String firstToken = RecordingEmailSender.tokenFrom(awaitEmails(email, 1).get(0));

        ResponseEntity<String> resend = post("/api/v1/auth/email/resend-verification", Map.of("email", email));
        String secondToken = RecordingEmailSender.tokenFrom(awaitEmails(email, 2).get(1));

        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(post("/api/v1/auth/email/verify", Map.of("token", firstToken)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/v1/auth/email/verify", Map.of("token", secondToken)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void resendForUnknownEmailLooksIdenticalAndSendsNothing() {
        String unknown = uniqueEmail();

        ResponseEntity<String> response = post("/api/v1/auth/email/resend-verification", Map.of("email", unknown));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> mail.sentTo(unknown).isEmpty());
    }

    @Test
    void weakPasswordIsRejectedAndNothingIsStored() {
        String email = uniqueEmail();

        ResponseEntity<String> response = post("/api/v1/auth/register",
                Map.of("email", email, "password", "Password1234"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("too common");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(rows).isZero();
    }

    @Test
    void concurrentRegistrationsOfOneEmailCreateExactlyOneAccount() throws Exception {
        String email = uniqueEmail();
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> responses = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            responses.add(pool.submit(() -> {
                start.await();
                return register(email);
            }));
        }

        start.countDown();
        for (Future<ResponseEntity<String>> response : responses) {
            assertThat(response.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        pool.shutdown();

        Integer accounts = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(accounts).isEqualTo(1);
    }

    /** Body without the per-request fields, which naturally differ. */
    private static String body(ResponseEntity<String> response) {
        return response.getBody()
                .replaceAll("\"timestamp\":\"[^\"]+\"", "")
                .replaceAll("\"requestId\":\"[^\"]+\"", "");
    }
}
