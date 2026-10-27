package com.ecommerce.backend.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a single {@link Clock} bean.
 *
 * <p>Code that needs "now" injects this Clock instead of calling {@code Instant.now()} directly.
 * Tests can then substitute a fixed clock, which matters for token expiry and lockout logic
 * in Phase 1.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
