package com.ecommerce.backend.auth.service;

/**
 * Where a request came from. Stored with refresh tokens so a user can later see and revoke
 * sessions ("Chrome on Windows, last used yesterday").
 *
 * <p>The IP is {@code request.getRemoteAddr()}. Behind a load balancer this is only the real
 * client IP because the prod profile enables {@code server.forward-headers-strategy}; never read
 * {@code X-Forwarded-For} by hand, since any client can forge it.
 */
public record ClientInfo(String ipAddress, String userAgent) {

    public static ClientInfo unknown() {
        return new ClientInfo(null, null);
    }
}
