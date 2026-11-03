package com.ecommerce.backend.profile;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.rbac.service.UserDirectory;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The profile over real HTTP, down to the rows, including how it ends when the account is erased. */
@ExtendWith(OutputCaptureExtension.class)
class ProfileIT extends AbstractIntegrationTest {

    private static final String PROFILE = "/api/v1/profile";
    private static final String DOMAIN = "@profile.test";

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

    @Autowired
    private UserDirectory directory;

    private AuthTestClient client;
    /** Erased rows no longer carry the test domain, so cleanup goes by ID. */
    private final List<UUID> created = new ArrayList<>();

    private record Account(UUID id, String token) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id)); // cascades to the profile
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private Account account() {
        String email = "p-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        return new Account(id, accessToken(client.login(email, PASSWORD)));
    }

    private ResponseEntity<String> save(Account account, Map<String, Object> body) {
        return client.send(HttpMethod.PUT, PROFILE, body, account.token());
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private long profileRows(Account account) {
        return jdbc.queryForObject("SELECT count(*) FROM user_profiles WHERE user_id = ?", Long.class, account.id());
    }

    private ResponseEntity<String> erase(Account account) {
        return client.postJson("/api/v1/auth/me/erase", Map.of("password", PASSWORD), account.token());
    }

    private static String randomPhone() {
        return "+4930" + ThreadLocalRandom.current().nextLong(10_000_000L, 99_999_999L);
    }

    @Test
    void aPersonSavesReadsAndReplacesTheirProfileAndNothingPersonalIsLogged(CapturedOutput logs) {
        Account ada = account();
        Account bob = account();
        String givenName = "Ada-" + UUID.randomUUID().toString().substring(0, 8);
        String phone = randomPhone();

        // Never saved: empty, without a version.
        JsonNode empty = data(client.get(PROFILE, ada.token()));
        assertThat(empty.has("version")).isFalse();
        assertThat(empty.has("givenName")).isFalse();

        // First save, without a version; input is normalized.
        Map<String, Object> first = new HashMap<>(Map.of("givenName", " " + givenName + " ", "familyName", "Lovelace",
                "phoneNumber", phone.substring(0, 3) + " " + phone.substring(3), "preferredLocale", "en-gb",
                "timeZone", "Europe/London"));
        JsonNode saved = data(save(ada, first));
        assertThat(saved.get("givenName").asText()).isEqualTo(givenName);
        assertThat(saved.get("phoneNumber").asText()).isEqualTo(phone);
        assertThat(saved.get("preferredLocale").asText()).isEqualTo("en-GB");
        assertThat(saved.get("version").asLong()).isZero();

        // A second "first save" (another device that also saw no profile) is refused.
        assertThat(save(ada, first).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Replace with the current version: what is left out is cleared.
        JsonNode replaced = data(save(ada, Map.of("givenName", givenName, "version", 0)));
        assertThat(replaced.has("familyName")).isFalse();
        assertThat(replaced.has("phoneNumber")).isFalse();
        assertThat(replaced.get("version").asLong()).isOne();

        // The old version no longer works.
        ResponseEntity<String> stale = save(ada, Map.of("givenName", "Augusta", "version", 0));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).contains("CONCURRENT_MODIFICATION");

        // Bob's token only ever reaches Bob's profile.
        assertThat(data(client.get(PROFILE, bob.token())).has("givenName")).isFalse();

        assertThat(logs.getAll()).contains("Profile saved: userId=" + ada.id())
                .doesNotContain(givenName).doesNotContain(phone).doesNotContain("Lovelace");
    }

    @Test
    void anUnknownTimeZoneIsRejectedWithTheField() {
        ResponseEntity<String> response = save(account(), Map.of("timeZone", "Mars/Olympus_Mons"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_FAILED").contains("timeZone");
    }

    @Test
    void erasureDeletesTheProfileAndTheStillValidAccessTokenCannotRecreateIt() {
        Account ada = account();
        data(save(ada, Map.of("givenName", "Ada", "phoneNumber", randomPhone())));

        assertThat(erase(ada).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(profileRows(ada)).isZero();

        // The JWT stays valid for up to 15 minutes; it must not be able to write personal data again.
        ResponseEntity<String> afterwards = save(ada, Map.of("givenName", "Ada"));
        assertThat(afterwards.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(profileRows(ada)).isZero();
    }

    @Test
    void aDeactivatedAccountKeepsItsProfileButCannotChangeIt() {
        Account ada = account();
        data(save(ada, Map.of("givenName", "Ada")));

        client.postJson("/api/v1/auth/me/deactivate", Map.of("password", PASSWORD), ada.token());

        assertThat(save(ada, Map.of("givenName", "Augusta", "version", 0)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT given_name FROM user_profiles WHERE user_id = ?", String.class, ada.id()))
                .as("deactivation is reversible, so the profile stays").isEqualTo("Ada");
    }

    /**
     * The race the account lock exists for: a save has checked the account and is writing when the
     * erasure starts. The erasure must wait for it and then delete what it wrote, instead of
     * leaving a fresh profile behind an erased account.
     */
    @Test
    void anErasureWaitsForASaveInProgressAndThenDeletesWhatItWrote() throws Exception {
        Account ada = account();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // What ProfileService.save does, paused between its write and its commit.
            Future<?> save = pool.submit(() -> tx.executeWithoutResult(status -> {
                assertThat(directory.lockIfActive(ada.id())).isTrue();
                jdbc.update("INSERT INTO user_profiles (user_id, given_name) VALUES (?, 'Ada')", ada.id());
                written.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<String>> erasure = pool.submit(() -> erase(ada));
            assertThatThrownBy(() -> erasure.get(1, TimeUnit.SECONDS)).as("the erasure waits for the lock")
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            save.get(10, TimeUnit.SECONDS);
            assertThat(erasure.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(profileRows(ada)).as("the profile written meanwhile is gone too").isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
