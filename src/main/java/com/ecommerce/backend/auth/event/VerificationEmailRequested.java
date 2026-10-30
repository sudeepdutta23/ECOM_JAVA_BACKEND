package com.ecommerce.backend.auth.event;

/**
 * Published inside the registration transaction; handled only after it commits.
 *
 * <p>Carries the raw token in memory so the email can contain it. It is never persisted or
 * logged, which is why {@code toString} hides it.
 */
public record VerificationEmailRequested(String email, String rawToken) {

    @Override
    public String toString() {
        return "VerificationEmailRequested[]";
    }
}
