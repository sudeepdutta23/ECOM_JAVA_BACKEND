package com.ecommerce.backend.auth.event;

/** The user deactivated their own account. */
public record AccountDeactivated(String email) {

    @Override
    public String toString() {
        return "AccountDeactivated[]";
    }
}
