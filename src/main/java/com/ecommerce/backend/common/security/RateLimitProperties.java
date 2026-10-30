package com.ecommerce.backend.common.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Per-client-IP request limits, bound from {@code app.rate-limit.*}.
 *
 * @param enabled           switch the limiter off (the shared test context does, see application-test.yml)
 * @param maxTrackedClients upper bound on remembered client keys, so a flood of IPs cannot exhaust memory
 * @param rules             ordered rules; the first one matching a request applies
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Min(1) long maxTrackedClients,
        @Valid List<Rule> rules) {

    public RateLimitProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * @param name     appears in logs and keys the buckets
     * @param method   HTTP method the rule applies to
     * @param paths    exact request paths
     * @param capacity requests allowed per period (also the burst size)
     * @param period   time for the bucket to refill completely
     */
    public record Rule(
            @NotBlank String name,
            @NotBlank String method,
            @NotEmpty List<String> paths,
            @Min(1) long capacity,
            @NotNull Duration period) {
    }
}
