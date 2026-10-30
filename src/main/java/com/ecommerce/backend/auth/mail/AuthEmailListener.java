package com.ecommerce.backend.auth.mail;

import com.ecommerce.backend.auth.event.AccountDeactivated;
import com.ecommerce.backend.auth.event.AccountLocked;
import com.ecommerce.backend.auth.event.DuplicateRegistrationAttempted;
import com.ecommerce.backend.auth.event.PasswordChanged;
import com.ecommerce.backend.auth.event.PasswordResetRequested;
import com.ecommerce.backend.auth.event.RefreshTokenReuseDetected;
import com.ecommerce.backend.auth.event.VerificationEmailRequested;
import com.ecommerce.backend.auth.service.AuthProperties;
import com.ecommerce.backend.common.mail.EmailMessage;
import com.ecommerce.backend.common.mail.EmailSender;
import com.ecommerce.backend.common.util.Masking;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends auth emails after the database transaction has committed.
 *
 * <p><b>AFTER_COMMIT:</b> if registration rolls back, no email goes out with a link to an
 * account that does not exist.
 *
 * <p><b>@Async:</b> SMTP can take hundreds of milliseconds or hang. Running on a pool thread
 * keeps the HTTP response fast, and makes "new account" and "existing account" responses take
 * the same time, which hides whether an email is registered.
 *
 * <p><b>Known trade-off:</b> if the process dies between commit and send, the email is lost.
 * The user can request a new link. Phase 13 replaces this with a transactional outbox.
 */
@Component
class AuthEmailListener {

    private static final Logger log = LoggerFactory.getLogger(AuthEmailListener.class);

    private final EmailSender emailSender;
    private final AuthProperties properties;

    AuthEmailListener(EmailSender emailSender, AuthProperties properties) {
        this.emailSender = emailSender;
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

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDuplicateRegistrationAttempted(DuplicateRegistrationAttempted event) {
        send(new EmailMessage(event.email(), "Someone tried to register with your email", """
                Someone just tried to create an account with this email address,
                but you already have one.

                If it was you, sign in instead, or reset your password at:
                %s/forgot-password

                If it was not you, no action is needed. Your account is unchanged.
                """.formatted(properties.frontendBaseUrl())));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountLocked(AccountLocked event) {
        send(new EmailMessage(event.email(), "Your account was temporarily locked", """
                We locked your account after several unsuccessful sign-in attempts.

                It unlocks automatically at %s (UTC). You do not need to do anything.

                If these attempts were not you, someone may be guessing your password.
                Consider resetting it at:
                %s/forgot-password
                """.formatted(event.lockedUntil().truncatedTo(ChronoUnit.MINUTES),
                properties.frontendBaseUrl())));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefreshTokenReuseDetected(RefreshTokenReuseDetected event) {
        send(new EmailMessage(event.email(), "We signed you out of a session for your security", """
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

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordChanged(PasswordChanged event) {
        send(new EmailMessage(event.email(), "Your password was changed", """
                The password for your account was just changed, and every device was signed out.

                If this was you, no action is needed.
                If it was NOT you, reset your password immediately:
                %s/forgot-password
                """.formatted(properties.frontendBaseUrl())));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountDeactivated(AccountDeactivated event) {
        send(new EmailMessage(event.email(), "Your account was deactivated", """
                Your account was deactivated and every device was signed out.

                If you did not do this, contact support so we can secure your account.
                """));
    }

    private void send(EmailMessage message) {
        try {
            emailSender.send(message);
        } catch (RuntimeException e) {
            // Never propagate: the registration already succeeded. Log for operators.
            log.error("Failed to send email: to={}, subject={}", Masking.email(message.to()), message.subject(), e);
        }
    }
}
