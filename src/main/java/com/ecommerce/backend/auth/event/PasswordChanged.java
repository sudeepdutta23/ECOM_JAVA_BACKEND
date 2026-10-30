package com.ecommerce.backend.auth.event;

/**
 * The password was changed or reset. The owner is always told: if it was not them, this email is
 * their only warning that someone else controls the account.
 */
public record PasswordChanged(String email) {

    @Override
    public String toString() {
        return "PasswordChanged[]";
    }
}
