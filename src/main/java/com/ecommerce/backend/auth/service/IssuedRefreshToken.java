package com.ecommerce.backend.auth.service;

import java.time.Instant;
import java.util.UUID;

/**
 * A new refresh token. The raw value goes into the cookie; only its hash is in the database.
 *
 * @param sessionId the token's family: one sign-in on one device, stable across rotations. It is
 *                  put into the access token as {@code sid}, so a request knows its own session.
 */
public record IssuedRefreshToken(String rawValue, Instant expiresAt, UUID sessionId) {

    @Override
    public String toString() {
        return "IssuedRefreshToken[sessionId=" + sessionId + ", expiresAt=" + expiresAt + "]";
    }
}
