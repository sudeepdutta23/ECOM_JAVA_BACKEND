package com.ecommerce.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.context.TestPropertySource;

/**
 * Rate limiting through the real filter chain.
 *
 * <p>Runs in its OWN application context (the property differs from the shared one), so it costs
 * one extra startup. That is the price of not letting limits interfere with every other test.
 */
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class RateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%@ratelimit.test'");
    }

    private ResponseEntity<String> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void eleventhLoginWithinAMinuteIsRejectedBeforeAnyWork() {
        Map<String, String> body = Map.of("email", "nobody@ratelimit.test", "password", "whatever-password");
        for (int i = 0; i < 10; i++) {
            assertThat(post("/api/v1/auth/login", body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> limited = post("/api/v1/auth/login", body);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(limited.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(limited.getBody()).contains("\"code\":\"TOO_MANY_REQUESTS\"").contains("\"requestId\"");
    }

    @Test
    void emailSendingEndpointsShareOneSmallBudget() {
        for (int i = 0; i < 5; i++) {
            String path = i % 2 == 0 ? "/api/v1/auth/password/forgot" : "/api/v1/auth/register";
            Object body = i % 2 == 0
                    ? Map.of("email", "victim@ratelimit.test")
                    : Map.of("email", "u" + UUID.randomUUID() + "@ratelimit.test", "password", "Correct-Horse-9-Battery");
            assertThat(post(path, body).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        // A sixth email-sending call from this IP is refused: no inbox flooding.
        assertThat(post("/api/v1/auth/password/forgot", Map.of("email", "victim@ratelimit.test")).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void unlimitedEndpointsStayAvailable() {
        for (int i = 0; i < 30; i++) {
            assertThat(rest.getForEntity("/api/v1/system/ping", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
