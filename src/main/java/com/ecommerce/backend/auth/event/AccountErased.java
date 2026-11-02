package com.ecommerce.backend.auth.event;

/**
 * An account was anonymised. Carries the email it had, only so that the confirmation can be sent
 * after commit; this event is the last place the address exists.
 */
public record AccountErased(String email) {

    @Override
    public String toString() {
        return "AccountErased[]";
    }
}
