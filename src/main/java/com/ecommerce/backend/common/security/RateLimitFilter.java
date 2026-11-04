package com.ecommerce.backend.common.security;

import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ProblemDetails;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits how often one client IP may call sensitive endpoints, using the token-bucket algorithm.
 *
 * <p><b>Token bucket:</b> each (rule, IP) pair has a bucket holding up to {@code capacity}
 * tokens. Every request takes one. Tokens refill steadily over {@code period}. Short bursts up
 * to the capacity are allowed; sustained hammering is not.
 *
 * <p><b>Why per IP, on top of account lockout?</b> Lockout protects one account. An attacker
 * trying one password against thousands of accounts never triggers it (credential stuffing).
 * Per-IP limits slow that down, and also cap parallel bursts against a single account.
 *
 * <p><b>Why a servlet filter before Spring Security?</b> Rejected requests should cost as little
 * as possible: no JWT parsing, no database, no password hashing.
 *
 * <p><b>Limitations (documented in SECURITY.md):</b> buckets live in this JVM's memory, so with
 * N instances the effective limit is N times higher; Phase 11 moves them to Redis. Attackers
 * with many IPs (botnets) need gateway or WAF protection.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    static final String LIMIT_HEADER = "X-RateLimit-Limit";
    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private record CompiledRule(RateLimitProperties.Rule rule, Set<String> paths) {
        boolean matches(String method, String path) {
            return rule.method().equalsIgnoreCase(method) && paths.contains(path);
        }
    }

    private final List<CompiledRule> rules;
    private final BucketStore buckets;
    private final ProblemResponseWriter writer;

    /** In-memory buckets: one server, and the unit tests. */
    RateLimitFilter(RateLimitProperties properties, ProblemResponseWriter writer, TimeMeter timeMeter) {
        this(properties, writer, new LocalBucketStore(properties.maxTrackedClients(), longestPeriod(properties), timeMeter));
    }

    RateLimitFilter(RateLimitProperties properties, ProblemResponseWriter writer, BucketStore buckets) {
        this.rules = properties.rules().stream()
                .map(rule -> new CompiledRule(rule, Set.copyOf(rule.paths())))
                .toList();
        this.buckets = buckets;
        this.writer = writer;
    }

    static Duration longestPeriod(RateLimitProperties properties) {
        return properties.rules().stream()
                .map(RateLimitProperties.Rule::period)
                .max(Comparator.naturalOrder())
                .orElse(Duration.ofMinutes(1));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        CompiledRule match = null;
        for (CompiledRule rule : rules) {
            if (rule.matches(request.getMethod(), path)) {
                match = rule;
                break;
            }
        }
        if (match == null) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitProperties.Rule rule = match.rule();
        // remoteAddr is the real client only because prod trusts the load balancer's
        // X-Forwarded-For via server.forward-headers-strategy. Never parse that header here.
        String key = rule.name() + "|" + request.getRemoteAddr();
        ConsumptionProbe probe;
        try {
            probe = buckets.bucket(key, () -> configuration(rule)).tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            // Fail open: with the shared store down, refusing everyone would turn a Redis outage into a
            // site outage. Login stays protected by the per-account lockout and the bounded hashing.
            log.error("Rate limit store unavailable, request let through: rule={}", rule.name(), e);
            chain.doFilter(request, response);
            return;
        }

        response.setHeader(LIMIT_HEADER, String.valueOf(rule.capacity()));
        response.setHeader(REMAINING_HEADER, String.valueOf(probe.getRemainingTokens()));

        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        log.warn("Rate limit exceeded: rule={}, clientIp={}, retryAfter={}s",
                rule.name(), request.getRemoteAddr(), retryAfterSeconds);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        writer.write(response, ProblemDetails.of(ErrorCode.TOO_MANY_REQUESTS,
                "Too many requests. Try again in " + retryAfterSeconds + " seconds.", path));
    }

    private static BucketConfiguration configuration(RateLimitProperties.Rule rule) {
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(rule.capacity()).refillGreedy(rule.capacity(), rule.period()))
                .build();
    }
}
