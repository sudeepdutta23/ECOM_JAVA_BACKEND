package com.ecommerce.backend.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.bucket4j.TimeMeter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RateLimitFilter} explicitly, with a fixed position in the filter chain:
 * right after {@code RequestIdFilter} (so rejections still carry a request ID) and before Spring
 * Security (so rejected requests cost nothing).
 *
 * <p>A {@code FilterRegistrationBean} instead of {@code @Component} keeps the filter out of
 * {@code @WebMvcTest} slices, which pick up every {@code Filter} bean automatically.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimitProperties properties,
                                                                   ObjectMapper objectMapper,
                                                                   ObjectProvider<RedisBucketStore> redisBuckets,
                                                                   MeterRegistry meters) {
        RateLimitFilter filter = properties.store() == RateLimitProperties.Store.REDIS
                ? new RateLimitFilter(properties, new ProblemResponseWriter(objectMapper), redisBuckets.getObject(), meters)
                : new RateLimitFilter(properties, new ProblemResponseWriter(objectMapper), TimeMeter.SYSTEM_NANOTIME, meters);
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("rateLimitFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/api/*");
        registration.setEnabled(properties.enabled());
        return registration;
    }

    /**
     * The shared store, on its own Lettuce connection (Bucket4j needs byte[] values). Built from
     * Spring Boot's Redis connection details, so local settings, environment variables and
     * Testcontainers all configure it the same way.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.rate-limit.store", havingValue = "redis")
    RedisBucketStore redisBucketStore(RedisConnectionDetails connection) {
        RedisConnectionDetails.Standalone standalone = connection.getStandalone();
        RedisURI.Builder uri = RedisURI.builder().withHost(standalone.getHost()).withPort(standalone.getPort())
                .withDatabase(standalone.getDatabase()).withTimeout(Duration.ofSeconds(2));
        if (connection.getPassword() != null) {
            uri = connection.getUsername() == null ? uri.withPassword(connection.getPassword().toCharArray())
                    : uri.withAuthentication(connection.getUsername(), connection.getPassword());
        }
        return new RedisBucketStore(RedisClient.create(uri.build()));
    }
}
