package com.ecommerce.backend.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every HTTP request a correlation ID.
 *
 * <ul>
 *   <li>Reuses the caller's {@code X-Request-Id} header when it is safe, so one ID can be
 *       traced across a gateway, this service, and downstream calls.</li>
 *   <li>Otherwise generates a random UUID.</li>
 *   <li>Stores the ID in the SLF4J MDC so every log line written on this thread includes it.</li>
 *   <li>Echoes the ID in the response header so a client can quote it in a support ticket.</li>
 * </ul>
 *
 * <p>Runs first in the filter chain so that even security failures are correlated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /**
     * Client-supplied IDs are accepted only if they match this pattern. This blocks log
     * injection (newlines, control characters) and oversized values.
     */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER_NAME));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled; a leftover value would leak into the next request.
            MDC.remove(MDC_KEY);
        }
    }

    /** Returns the request ID bound to the current thread, or {@code null} outside a request. */
    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }

    static String resolveRequestId(String incoming) {
        if (incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
