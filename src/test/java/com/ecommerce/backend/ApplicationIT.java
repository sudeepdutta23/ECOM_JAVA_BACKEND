package com.ecommerce.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.common.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Full application on a random port against real PostgreSQL (Testcontainers).
 * Proves the whole Phase 0 stack works together: config, Flyway, JPA validation,
 * Actuator, OpenAPI, filters and error handling.
 */
class ApplicationIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliedBaselineMigration() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void actuatorHealthIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void readinessProbeIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/readiness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void pingReturnsEnvelopeAndEchoesRequestId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestIdFilter.HEADER_NAME, "it-test-request-0001");

        ResponseEntity<String> response = rest.exchange("/api/v1/system/ping",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(RequestIdFilter.HEADER_NAME)).isEqualTo("it-test-request-0001");
        assertThat(response.getBody())
                .contains("\"success\":true")
                .contains("\"requestId\":\"it-test-request-0001\"");
    }

    @Test
    void unknownRouteWithoutTokenIsDeniedWithProblemDetail() {
        // Default deny: security rejects the request before routing, so unknown paths reveal nothing.
        ResponseEntity<String> response = rest.getForEntity("/api/v1/does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.getBody()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void openApiSpecIsPublished() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("/api/v1/system/ping");
    }

    /**
     * Keeps the API documentation in sync with the code: every auth endpoint must appear in the
     * generated OpenAPI spec, and protected ones must declare the bearer scheme.
     */
    @Test
    void openApiSpecDocumentsEveryAuthEndpoint() {
        String spec = rest.getForObject("/v3/api-docs", String.class);

        assertThat(spec).contains(
                "\"/api/v1/auth/register\"", "\"/api/v1/auth/email/verify\"", "\"/api/v1/auth/email/resend-verification\"",
                "\"/api/v1/auth/login\"", "\"/api/v1/auth/refresh\"", "\"/api/v1/auth/logout\"",
                "\"/api/v1/auth/logout-all\"", "\"/api/v1/auth/password/change\"", "\"/api/v1/auth/password/forgot\"",
                "\"/api/v1/auth/password/reset\"", "\"/api/v1/auth/me\"", "\"/api/v1/auth/me/deactivate\"");
        assertThat(spec).contains("\"security\":[{\"bearerAuth\":[]}]");
        assertThat(spec).contains("\"RegisterRequest\"", "\"LoginRequest\"", "\"TokenResponse\"", "\"MeResponse\"");
    }
}
