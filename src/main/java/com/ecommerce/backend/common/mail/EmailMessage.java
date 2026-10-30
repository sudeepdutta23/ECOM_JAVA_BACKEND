package com.ecommerce.backend.common.mail;

import java.util.Objects;

/**
 * A plain-text email.
 *
 * <p>{@code toString} omits the body because bodies often contain one-time links.
 */
public record EmailMessage(String to, String subject, String body) {

    public EmailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
    }

    @Override
    public String toString() {
        return "EmailMessage[subject=" + subject + "]";
    }
}
