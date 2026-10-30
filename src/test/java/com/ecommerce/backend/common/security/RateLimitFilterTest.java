package com.ecommerce.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    /** A clock we move by hand, so refill can be tested without sleeping. */
    static final class ManualTimeMeter implements TimeMeter {
        private final AtomicLong nanos = new AtomicLong();

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final ManualTimeMeter clock = new ManualTimeMeter();
    private final RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(true, 1_000, List.of(
            new RateLimitProperties.Rule("login", "POST", List.of("/api/v1/auth/login"), 3, Duration.ofMinutes(1)),
            new RateLimitProperties.Rule("email", "POST", List.of("/api/v1/auth/register", "/api/v1/auth/password/forgot"),
                    2, Duration.ofMinutes(15)))),
            new ProblemResponseWriter(mapper), clock);

    private MockHttpServletResponse call(String method, String path, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (response.getStatus() == 200) {
            assertThat(chain.getRequest()).as("request passed on").isNotNull();
        }
        return response;
    }

    @Test
    void allowsUpToCapacityThenRejectsWith429() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("POST", "/api/v1/auth/login", "203.0.113.1").getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse rejected = call("POST", "/api/v1/auth/login", "203.0.113.1");

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentType()).startsWith("application/problem+json");
        assertThat(mapper.readTree(rejected.getContentAsString()).get("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(Long.parseLong(rejected.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(rejected.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void reportsRemainingRequests() throws Exception {
        MockHttpServletResponse first = call("POST", "/api/v1/auth/login", "203.0.113.2");

        assertThat(first.getHeader("X-RateLimit-Limit")).isEqualTo("3");
        assertThat(first.getHeader("X-RateLimit-Remaining")).isEqualTo("2");
    }

    @Test
    void eachClientIpHasItsOwnBucket() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("POST", "/api/v1/auth/login", "203.0.113.3");
        }

        assertThat(call("POST", "/api/v1/auth/login", "203.0.113.3").getStatus()).isEqualTo(429);
        assertThat(call("POST", "/api/v1/auth/login", "198.51.100.9").getStatus()).isEqualTo(200);
    }

    @Test
    void pathsInOneRuleShareABucketButRulesAreIndependent() throws Exception {
        call("POST", "/api/v1/auth/register", "203.0.113.4");
        call("POST", "/api/v1/auth/password/forgot", "203.0.113.4");

        assertThat(call("POST", "/api/v1/auth/register", "203.0.113.4").getStatus()).isEqualTo(429);
        assertThat(call("POST", "/api/v1/auth/login", "203.0.113.4").getStatus()).isEqualTo(200);
    }

    @Test
    void tokensRefillOverThePeriod() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("POST", "/api/v1/auth/login", "203.0.113.5");
        }
        assertThat(call("POST", "/api/v1/auth/login", "203.0.113.5").getStatus()).isEqualTo(429);

        clock.advance(Duration.ofSeconds(20)); // one third of a minute = one of three tokens

        assertThat(call("POST", "/api/v1/auth/login", "203.0.113.5").getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/v1/auth/login", "203.0.113.5").getStatus()).isEqualTo(429);
    }

    @Test
    void unlistedEndpointsAndMethodsAreNotLimited() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(call("GET", "/api/v1/auth/login", "203.0.113.6").getStatus()).isEqualTo(200);
            assertThat(call("POST", "/api/v1/orders", "203.0.113.6").getStatus()).isEqualTo(200);
        }
    }
}
