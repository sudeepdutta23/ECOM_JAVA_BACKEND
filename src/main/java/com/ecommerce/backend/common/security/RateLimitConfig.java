package com.ecommerce.backend.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.TimeMeter;
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
                                                                   ObjectMapper objectMapper) {
        RateLimitFilter filter = new RateLimitFilter(properties, new ProblemResponseWriter(objectMapper),
                TimeMeter.SYSTEM_NANOTIME);
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("rateLimitFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/api/*");
        registration.setEnabled(properties.enabled());
        return registration;
    }
}
