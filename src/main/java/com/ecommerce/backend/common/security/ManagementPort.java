package com.ecommerce.backend.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The port of the separate management server ({@code management.server.port}), once it started.
 *
 * <p>Production serves the actuator on its own port, which the load balancer never publishes: only
 * Prometheus, inside the network, reaches it. {@link #matcher()} lets the security rules open the
 * metrics scrape on that port alone. Without a separate port (dev, tests) it matches nothing, so the
 * scrape is never reachable on the public port.
 *
 * <p>A bean of {@link SecurityConfig}, so every context with the security rules (controller test
 * slices included) has it.
 */
public class ManagementPort implements ApplicationListener<WebServerInitializedEvent> {

    static final String NAMESPACE = "management";

    private volatile int port = -1;

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (NAMESPACE.equals(event.getApplicationContext().getServerNamespace())) {
            port = event.getWebServer().getPort();
        }
    }

    /** Matches requests that arrived on the management port. */
    public RequestMatcher matcher() {
        return (HttpServletRequest request) -> port > 0 && request.getLocalPort() == port;
    }
}
