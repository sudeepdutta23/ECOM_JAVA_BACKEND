package com.ecommerce.backend.auth.event;

import java.time.Instant;

/**
 * Too many wrong passwords locked an account. The owner is emailed: either they mistyped,
 * or someone is guessing their password and they should know.
 */
public record AccountLocked(String email, Instant lockedUntil) {

    @Override
    public String toString() {
        return "AccountLocked[lockedUntil=" + lockedUntil + "]";
    }
}
