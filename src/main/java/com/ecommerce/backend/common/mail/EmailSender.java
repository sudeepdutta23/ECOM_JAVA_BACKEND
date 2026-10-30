package com.ecommerce.backend.common.mail;

/**
 * Sends an email. Modules depend on this interface, never on SMTP or a vendor SDK, so the
 * transport can change (SMTP, Amazon SES, SendGrid) without touching business code.
 *
 * <p>This is one of the few interfaces in the project that earns its place: it has several real
 * implementations (SMTP, logging, and a recording fake in tests).
 *
 * <p>Phase 7 (Notifications) will move this into its own module with templates and retries.
 */
public interface EmailSender {

    /**
     * @throws EmailDeliveryException if the message could not be handed to the mail server
     */
    void send(EmailMessage message);
}
