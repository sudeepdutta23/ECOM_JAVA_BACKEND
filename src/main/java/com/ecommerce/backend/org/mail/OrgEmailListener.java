package com.ecommerce.backend.org.mail;

import com.ecommerce.backend.common.mail.EmailMessage;
import com.ecommerce.backend.common.mail.EmailSender;
import com.ecommerce.backend.common.util.Masking;
import com.ecommerce.backend.org.event.InvitationCreated;
import com.ecommerce.backend.org.service.OrgProperties;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends organisation emails after commit, on a pool thread, like the auth module's listener: a
 * rolled-back invitation never sends a link, and SMTP latency never slows the request.
 */
@Component
class OrgEmailListener {

    private static final Logger log = LoggerFactory.getLogger(OrgEmailListener.class);

    private final EmailSender emailSender;
    private final OrgProperties properties;

    OrgEmailListener(EmailSender emailSender, OrgProperties properties) {
        this.emailSender = emailSender;
        this.properties = properties;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvitationCreated(InvitationCreated event) {
        String link = properties.frontendBaseUrl() + "/invitations/accept?token=" + event.rawToken();
        // The organisation name is chosen by sellers: it goes into the body only, never the subject,
        // so a name like "Your bank: verify now" cannot dress up the email as something else.
        EmailMessage message = new EmailMessage(event.email(), "You are invited to work for a seller", """
                You have been invited to join the seller "%s" as %s.

                Sign in with this email address (or create an account with it), then accept here:

                %s

                The invitation expires at %s (UTC) and can be used once.
                If you do not know this seller, ignore this email; nothing changes.
                """.formatted(event.organizationName(), event.role(), link,
                event.expiresAt().truncatedTo(ChronoUnit.MINUTES)));
        try {
            emailSender.send(message);
        } catch (RuntimeException e) {
            log.error("Failed to send invitation email: to={}", Masking.email(message.to()), e);
        }
    }
}
