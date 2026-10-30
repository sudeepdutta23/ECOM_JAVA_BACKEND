package com.ecommerce.backend.auth.service;

import java.time.Instant;

/**
 * A new refresh token. The raw value goes into the cookie; only its hash is in the database.
 */
public record IssuedRefreshToken(String rawValue, Instant expiresAt) {

    @Override
    public String toString() {
        return "IssuedRefreshToken[expiresAt=" + expiresAt + "]";
    }
}
