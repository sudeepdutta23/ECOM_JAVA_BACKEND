package com.ecommerce.backend.common.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables Spring Data auditing, which fills {@code @CreatedDate} and {@code @LastModifiedDate}.
 *
 * <p>Timestamps come from the shared {@link Clock}, so tests can control them.
 *
 * <p>This lives in its own class, not on {@code Application}, on purpose: web-slice tests
 * ({@code @WebMvcTest}) skip this configuration and therefore don't need a JPA setup.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
