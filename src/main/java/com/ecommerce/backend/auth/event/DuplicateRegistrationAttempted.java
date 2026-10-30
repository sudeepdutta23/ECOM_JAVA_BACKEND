package com.ecommerce.backend.auth.event;

/**
 * Someone tried to register with an email that already belongs to an account.
 *
 * <p>The API answers exactly as for a new registration, so the caller learns nothing.
 * The real owner gets an email instead, which tells them about the attempt and how to sign in
 * or reset their password.
 */
public record DuplicateRegistrationAttempted(String email) {

    @Override
    public String toString() {
        return "DuplicateRegistrationAttempted[]";
    }
}
