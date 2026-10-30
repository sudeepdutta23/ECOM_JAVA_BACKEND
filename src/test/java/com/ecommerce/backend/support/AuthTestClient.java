package com.ecommerce.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Drives the auth API the way a real client would, so integration tests read like user journeys.
 */
public class AuthTestClient {

    public static final String PASSWORD = "Correct-Horse-9-Battery";

    private final TestRestTemplate rest;
    private final RecordingEmailSender mail;

    public AuthTestClient(TestRestTemplate rest, RecordingEmailSender mail) {
        this.rest = rest;
        this.mail = mail;
    }

    public ResponseEntity<String> postJson(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, "AuthTestClient/1.0");
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    public ResponseEntity<String> postJson(String path, Object body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, "AuthTestClient/1.0");
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    public ResponseEntity<String> get(String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    public void register(String email) {
        ResponseEntity<String> response = postJson("/api/v1/auth/register", Map.of("email", email, "password", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    /** Registers, waits for the verification email, and verifies: an ACTIVE account. */
    public void registerAndVerify(String email) {
        int before = mail.sentTo(email).size();
        register(email);
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.sentTo(email).size() > before);
        String token = RecordingEmailSender.tokenFrom(mail.sentTo(email).get(before));
        ResponseEntity<String> verified = postJson("/api/v1/auth/email/verify", Map.of("token", token));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    public ResponseEntity<String> login(String email, String password) {
        return postJson("/api/v1/auth/login", Map.of("email", email, "password", password));
    }

    /** POST with the refresh cookie (as a browser would send it) and optionally a bearer token. */
    public ResponseEntity<String> postWithCookie(String path, String refreshCookieValue, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "AuthTestClient/1.0");
        if (refreshCookieValue != null) {
            headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshCookieValue);
        }
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    public ResponseEntity<String> refresh(String refreshCookieValue) {
        return postWithCookie("/api/v1/auth/refresh", refreshCookieValue, null);
    }

    public ResponseEntity<String> logout(String refreshCookieValue) {
        return postWithCookie("/api/v1/auth/logout", refreshCookieValue, null);
    }

    public ResponseEntity<String> logoutAll(String accessToken) {
        return postWithCookie("/api/v1/auth/logout-all", null, accessToken);
    }

    /** Response body without the fields that differ on every request. */
    public static String stableBody(ResponseEntity<String> response) {
        return response.getBody()
                .replaceAll("\"timestamp\":\"[^\"]+\"", "")
                .replaceAll("\"requestId\":\"[^\"]+\"", "");
    }

    /** Extracts {@code "accessToken":"..."} from a login or refresh body. */
    public static String accessToken(ResponseEntity<String> response) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(response.getBody());
        assertThat(m.find()).as("accessToken in body").isTrue();
        return m.group(1);
    }

    /** The full Set-Cookie header for the refresh token. */
    public static String refreshCookieHeader(ResponseEntity<String> response) {
        return response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("refresh_token="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No refresh_token cookie"));
    }

    /** Just the cookie value. */
    public static String refreshCookieValue(ResponseEntity<String> response) {
        String header = refreshCookieHeader(response);
        return header.substring("refresh_token=".length(), header.indexOf(';'));
    }
}
