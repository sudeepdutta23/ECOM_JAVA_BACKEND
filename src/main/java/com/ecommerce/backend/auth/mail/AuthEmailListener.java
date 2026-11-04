package com.ecommerce.backend.auth.mail;

import com.ecommerce.backend.auth.event.AccountDeactivated;
import com.ecommerce.backend.auth.event.AccountErased;
import com.ecommerce.backend.auth.event.AccountLocked;
import com.ecommerce.backend.auth.event.DuplicateRegistrationAttempted;
import com.ecommerce.backend.auth.event.PasswordChanged;
import com.ecommerce.backend.auth.event.PasswordResetRequested;
import com.ecommerce.backend.auth.event.RefreshTokenReuseDetected;
import com.ecommerce.backend.auth.event.VerificationEmailRequested;
import com.ecommerce.backend.auth.service.AuthProperties;
import com.ecommerce.backend.common.mail.EmailMessage;
import com.ecommerce.backend.common.mail.EmailOutbox;
import com.ecommerce.backend.common.mail.EmailSender;
import com.ecommerce.backend.common.util.Masking;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends auth emails, in one of two ways:
 *
 * <ul>
 *   <li><b>Security notices</b> (duplicate registration, lockout, token reuse, password change,
 *       deactivation) go to the {@link EmailOutbox} in the caller's transaction: they exist exactly
 *       when the change committed, and a crash after commit loses none.</li>
 *   <li><b>One-time links</b> (verification, password reset) and the erasure confirmation are sent
 *       after commit ({@code AFTER_COMMIT}, so a rolled-back registration mails no link) on a pool
 *       thread ({@code @Async}, so SMTP never slows the response, and "new account" and "existing
 *       account" take the same time). Best effort, on purpose: the outbox would store a raw token,
 *       or an erased person's address. A lost link can be requested again.</li>
 * </ul>
 */
@Component
class AuthEmailListener {

    private static final Logger log = LoggerFactory.getLogger(AuthEmailListener.class);

    private final EmailSender emailSender;
    private final EmailOutbox outbox;
    private final AuthProperties properties;

    AuthEmailListener(EmailSender emailSender, EmailOutbox outbox, AuthProperties properties) {
        this.emailSender = emailSender;
        this.outbox = outbox;
        this.properties = properties;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationEmailRequested(VerificationEmailRequested event) {
        String link = properties.frontendBaseUrl() + "/verify-email?token=" + event.rawToken();
        send(new EmailMessage(event.email(), "Confirm your email address", """
                Welcome!

                Confirm your email address to activate your account:

                %s

                This link expires in %d hours and can be used once.
                If you did not create an account, you can ignore this email.
                """.formatted(link, properties.emailVerificationTtl().toHours())));
    }

    @EventListener
    public void onDuplicateRegistrationAttempted(DuplicateRegistrationAttempted event) {
        outbox.enqueue(new EmailMessage(event.email(), "Someone tried to register with your email", """
                Someone just tried to create an account with this email address,
                but you already have one.

                If it was you, sign in instead, or reset your password at:
                %s/forgot-password

                If it was not you, no action is needed. Your account is unchanged.
                """.formatted(properties.frontendBaseUrl())));
    }

    @EventListener
    public void onAccountLocked(AccountLocked event) {
        outbox.enqueue(new EmailMessage(event.email(), "Your account was temporarily locked", """
                We locked your account after several unsuccessful sign-in attempts.

                It unlocks automatically at %s (UTC). You do not need to do anything.

                If these attempts were not you, someone may be guessing your password.
                Consider resetting it at:
                %s/forgot-password
                """.formatted(event.lockedUntil().truncatedTo(ChronoUnit.MINUTES),
                properties.frontendBaseUrl())));
    }

    @EventListener
    public void onRefreshTokenReuseDetected(RefreshTokenReuseDetected event) {
        outbox.enqueue(new EmailMessage(event.email(), "We signed you out of a session for your security", """
                We noticed an old sign-in token being reused, which can mean it was copied
                from one of your devices. To protect you, we ended that session.

                Sign in again on that device. If you did not expect this, reset your password:
                %s/forgot-password
                """.formatted(properties.frontendBaseUrl())));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequested event) {
        String link = properties.frontendBaseUrl() + "/reset-password?token=" + event.rawToken();
        send(new EmailMessage(event.email(), "Reset your password", """
                We received a request to reset your password. Choose a new one here:

                %s

                This link expires in %d minutes and can be used once.
                If you did not ask for this, ignore this email; your password stays the same.
                """.formatted(link, properties.passwordResetTtl().toMinutes())));
    }

    @EventListener
    public void onPasswordChanged(PasswordChanged event) {
        outbox.enqueue(new EmailMessage(event.email(), "Your password was changed", """
                The password for your account was just changed, and every device was signed out.

                If this was you, no action is needed.
                If it was NOT you, reset your password immediately:
                %s/forgot-password
                """.formatted(properties.frontendBaseUrl())));
    }

    @EventListener
    public void onAccountDeactivated(AccountDeactivated event) {
        outbox.enqueue(new EmailMessage(event.email(), "Your account was deactivated", """
                Your account was deactivated and every device was signed out.

                If you did not do this, contact support so we can secure your account.
                """));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountErased(AccountErased event) {
        send(new EmailMessage(event.email(), "Your account was erased", """
                As requested, your account and the personal data stored with it were erased,
                and every device was signed out. This cannot be undone.

                This is the last email we send to this address. You are welcome to
                register again at any time.
                """));
    }

    private void send(EmailMessage message) {
        try {
            emailSender.send(message);
        } catch (RuntimeException e) {
            // Never propagate: the change already committed. Log for operators.
            log.error("Failed to send email: to={}, subject={}", Masking.email(message.to()), message.subject(), e);
        }
    }
}
