package com.ecommerce.backend.auth.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the cookie that carries the refresh token (ADR-009).
 *
 * <ul>
 *   <li>{@code HttpOnly}: JavaScript cannot read it, so an XSS bug cannot steal it.</li>
 *   <li>{@code Secure}: only sent over HTTPS (switched off in dev for plain-HTTP testing).</li>
 *   <li>{@code SameSite=Strict}: never sent on requests started by another site (CSRF).</li>
 *   <li>{@code Path=/api/v1/auth}: only sent to auth endpoints, not to every API call, which
 *       keeps it out of logs and proxies for the rest of the API.</li>
 *   <li>{@code Max-Age} equals the token lifetime, so the browser drops it when it is useless.</li>
 * </ul>
 */
@Component
public class RefreshTokenCookie {

    public static final String NAME = "refresh_token";
    public static final String PATH = "/api/v1/auth";

    private final Duration maxAge;
    private final boolean secure;

    public RefreshTokenCookie(@Value("${app.auth.refresh-token-ttl}") Duration maxAge,
                              @Value("${app.auth.refresh-cookie-secure}") boolean secure) {
        this.maxAge = maxAge;
        this.secure = secure;
    }

    public ResponseCookie create(String rawToken) {
        return base(rawToken).maxAge(maxAge).build();
    }

    /** Tells the browser to delete the cookie (used by logout in Step 1.6). */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH);
    }
}
