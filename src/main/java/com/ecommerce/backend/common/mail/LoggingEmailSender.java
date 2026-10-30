package com.ecommerce.backend.common.mail;

import com.ecommerce.backend.common.util.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback when no SMTP server is configured: logs that an email WOULD have been sent.
 *
 * <p>The body is deliberately not logged, since it contains one-time links that grant access
 * to an account. Use Mailpit to read emails locally.
 */
class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.warn("No SMTP server configured (spring.mail.host); email NOT sent: to={}, subject={}",
                Masking.email(message.to()), message.subject());
    }
}
