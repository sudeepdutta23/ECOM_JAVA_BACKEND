package com.ecommerce.backend.auth.event;

/** A reset link must be emailed. Carries the raw token in memory only; never logged. */
public record PasswordResetRequested(String email, String rawToken) {

    @Override
    public String toString() {
        return "PasswordResetRequested[]";
    }
}
