package com.ecommerce.backend.auth;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static com.ecommerce.backend.support.AuthTestClient.refreshCookieValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.common.security.OpaqueTokens;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code /me/sessions} over real HTTP: one entry per signed-in device. */
class SessionsIT extends AbstractIntegrationTest {

    private static final String SESSIONS = "/api/v1/auth/me/sessions";
    private static final String DOMAIN = "@sessions.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private AuthTestClient client;
    private String email;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        email = "s-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private JsonNode list(String accessToken) throws Exception {
        ResponseEntity<String> response = client.get(SESSIONS, accessToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private String sessionOf(String refreshCookie) {
        return jdbc.queryForObject("SELECT family_id FROM refresh_tokens WHERE token_hash = ?",
                UUID.class, OpaqueTokens.hash(refreshCookie)).toString();
    }

    private String sidClaim(String accessToken) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
        return mapper.readTree(payload).get("sid").asText();
    }

    private ResponseEntity<String> end(String sessionId, String accessToken) {
        return client.send(HttpMethod.DELETE, SESSIONS + "/" + sessionId, null, accessToken);
    }

    @Test
    void eachSignInIsOneSessionAndTheCurrentOneIsMarked() throws Exception {
        ResponseEntity<String> phone = client.login(email, PASSWORD);
        ResponseEntity<String> laptop = client.login(email, PASSWORD);

        JsonNode sessions = list(accessToken(laptop));

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).get("sessionId").asText()).isEqualTo(sessionOf(refreshCookieValue(laptop)));
        assertThat(sessions.get(0).get("current").asBoolean()).isTrue();
        assertThat(sessions.get(1).get("sessionId").asText()).isEqualTo(sessionOf(refreshCookieValue(phone)));
        assertThat(sessions.get(1).get("current").asBoolean()).isFalse();
        assertThat(sessions.get(0).get("userAgent").asText()).isEqualTo("AuthTestClient/1.0");
        assertThat(sessions.get(0).has("ip")).isTrue();
    }

    @Test
    void aSessionKeepsItsIdAcrossRefreshesAndMovesToTheTop() throws Exception {
        ResponseEntity<String> phone = client.login(email, PASSWORD);
        String phoneSession = sessionOf(refreshCookieValue(phone));
        ResponseEntity<String> laptop = client.login(email, PASSWORD);

        ResponseEntity<String> refreshed = client.refresh(refreshCookieValue(phone));
        JsonNode sessions = list(accessToken(laptop));

        assertThat(sidClaim(accessToken(phone))).isEqualTo(phoneSession);
        assertThat(sidClaim(accessToken(refreshed))).isEqualTo(phoneSession); // same session after rotation
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).get("sessionId").asText()).isEqualTo(phoneSession);
        assertThat(sessions.get(0).get("lastActiveAt").asText())
                .isGreaterThan(sessions.get(0).get("startedAt").asText());
    }

    @Test
    void endingAnotherDeviceSignsItOutOnly() throws Exception {
        ResponseEntity<String> phone = client.login(email, PASSWORD);
        ResponseEntity<String> laptop = client.login(email, PASSWORD);

        ResponseEntity<String> ended = end(sessionOf(refreshCookieValue(phone)), accessToken(laptop));

        assertThat(ended.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.refresh(refreshCookieValue(phone)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(list(accessToken(laptop))).hasSize(1);
        assertThat(client.refresh(refreshCookieValue(laptop)).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Idempotent for the owner.
        assertThat(end(sessionOf(refreshCookieValue(phone)), accessToken(laptop)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void endingTheCurrentSessionWorksLikeLogout() throws Exception {
        ResponseEntity<String> login = client.login(email, PASSWORD);

        assertThat(end(sidClaim(accessToken(login)), accessToken(login)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.refresh(refreshCookieValue(login)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void someoneElsesSessionIs404AndKeepsWorking() {
        String otherEmail = "other-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(otherEmail);
        ResponseEntity<String> victim = client.login(otherEmail, PASSWORD);
        ResponseEntity<String> attacker = client.login(email, PASSWORD);

        ResponseEntity<String> response = end(sessionOf(refreshCookieValue(victim)), accessToken(attacker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.refresh(refreshCookieValue(victim)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(end(UUID.randomUUID().toString(), accessToken(attacker)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void logoutAllEmptiesTheList() throws Exception {
        client.login(email, PASSWORD);
        String token = accessToken(client.login(email, PASSWORD));

        client.logoutAll(token);

        assertThat(list(token)).isEmpty(); // the access token still works for up to 15 minutes
    }

    @Test
    void sessionsNeedAToken() {
        assertThat(client.get(SESSIONS, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
