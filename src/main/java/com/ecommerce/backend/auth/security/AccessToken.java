package com.ecommerce.backend.auth.security;

import java.time.Instant;

/**
 * A freshly signed access token.
 *
 * @param value     the compact JWT string; never log it
 * @param expiresAt absolute expiry
 * @param expiresIn lifetime in seconds, as returned to clients ({@code expires_in} in OAuth2)
 */
public record AccessToken(String value, Instant expiresAt, long expiresIn) {

    /** Keeps the token out of logs and exception messages. */
    @Override
    public String toString() {
        return "AccessToken[expiresAt=" + expiresAt + "]";
    }
}
