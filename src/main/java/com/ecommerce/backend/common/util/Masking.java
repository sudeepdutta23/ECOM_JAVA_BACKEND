package com.ecommerce.backend.common.util;

/**
 * Masks personal data before it is written to logs.
 *
 * <p>Logs are copied to aggregators, backups and support tickets, and are rarely protected as
 * well as the database. Keeping just enough to correlate ("a***@example.com") is the usual
 * compromise between privacy and debuggability.
 */
public final class Masking {

    private Masking() {
    }

    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "<none>";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
