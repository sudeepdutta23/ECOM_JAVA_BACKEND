package com.ecommerce.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.support.TestMailConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The production layout: the actuator on its own port, with metrics export on. Its own application
 * context (a separate management server cannot share the others'), so it covers only what that
 * layout changes: where the scrape is reachable, and what it contains.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@AutoConfigureObservability
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@ActiveProfiles("test")
class ObservabilityIT {

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    private ResponseEntity<String> management(String path) {
        return rest.getForEntity("http://localhost:" + managementPort + path, String.class);
    }

    @Test
    void theScrapeIsServedOnTheManagementPortWithTheApplicationAndBusinessMeters() {
        rest.postForEntity("/api/v1/auth/login", Map.of("email", "nobody@observability.test", "password", "wrong-password-1"),
                String.class);

        ResponseEntity<String> scrape = management("/actuator/prometheus");

        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody())
                .contains("application=\"ecommerce-backend\"")
                .contains("http_server_requests_seconds_count")
                .contains("hikaricp_connections_active")
                .contains("jvm_memory_used_bytes")
                .contains("emails_outbox_pending")
                .contains("auth_logins_total{application=\"ecommerce-backend\",outcome=\"auth_invalid_credentials\"}")
                .as("no email ever becomes a label").doesNotContain("observability.test");
    }

    @Test
    void theScrapeIsNotReachableOnThePublicPort() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(response.getBody()).doesNotContain("jvm_memory");
    }

    @Test
    void probesAnswerOnTheManagementPortAndNothingElseIsExposed() {
        assertThat(management("/actuator/health/readiness").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(management("/actuator/env").getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(management("/actuator/heapdump").getStatusCode().is2xxSuccessful()).isFalse();
    }
}
