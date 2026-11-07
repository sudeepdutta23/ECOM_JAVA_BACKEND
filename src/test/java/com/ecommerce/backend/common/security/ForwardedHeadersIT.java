package com.ecommerce.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The production proxy setup. A load balancer appends the address it saw to X-Forwarded-For, so only
 * the right end of the header can be trusted: everything to its left is whatever the client sent.
 * The test client connects from 127.0.0.1, which plays the trusted load balancer.
 */
@TestPropertySource(properties = {"app.rate-limit.enabled=true", "server.forward-headers-strategy=native"})
class ForwardedHeadersIT extends AbstractIntegrationTest {

    private static final int LOGIN_CAPACITY = 10;

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<String> login(String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        return rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(
                Map.of("email", "nobody@forwarded.test", "password", "wrong-password-1"), headers), String.class);
    }

    private static String randomAddress() {
        int value = UUID.randomUUID().hashCode();
        return "198.18." + (value & 0xff) + "." + ((value >> 8) & 0xff);
    }

    @Test
    void aClientCannotEscapeTheRateLimitBySpoofingForwardedFor() {
        String realClient = "203.0.113.7";
        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            assertThat(login(randomAddress() + ", " + realClient).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(login(randomAddress() + ", " + realClient).getStatusCode())
                .as("a fresh spoofed address on the left must not give a fresh bucket")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void differentClientsBehindTheLoadBalancerHaveTheirOwnLimits() {
        String busy = "203.0.113.8";
        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            login(busy);
        }
        assertThat(login(busy).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(login("203.0.113.9").getStatusCode()).as("the load balancer's own address is not the key")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Without CORS headers on the 429, a browser reports a network error and hides Retry-After from the page. */
    @Test
    void aBrowserCanReadARateLimitRejection() {
        String client = "203.0.113.10";
        for (int i = 0; i < LOGIN_CAPACITY; i++) {
            login(client);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", client);
        headers.setOrigin("http://localhost:3000");

        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(
                Map.of("email", "nobody@forwarded.test", "password", "wrong-password-1"), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:3000");
        assertThat(response.getHeaders().getAccessControlExposeHeaders()).contains("Retry-After");
        assertThat(response.getHeaders().get("Access-Control-Allow-Origin")).as("once, not doubled by the later filter").hasSize(1);
    }

    @Test
    void aRequestTheLoadBalancerReceivedOverHttpsCountsAsSecure() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-Proto", "https");

        ResponseEntity<String> secure = rest.exchange("/api/v1/system/ping", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(secure.getHeaders().getFirst("Strict-Transport-Security")).isNotNull();
    }
}
