package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.TokenPurpose;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.event.DuplicateRegistrationAttempted;
import com.ecommerce.backend.auth.event.VerificationEmailRequested;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.common.util.Masking;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Registration, email verification and resending the verification email.
 *
 * <p><b>Enumeration resistance:</b> {@link #register} and {@link #resendVerification} return
 * nothing and never throw for "email exists" or "email unknown". The controller answers every
 * call with the same 202, and the account owner is informed by email instead.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository users;
    private final AccountTokenService accountTokens;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transactions;
    private final AuthProperties properties;
    private final Clock clock;

    public RegistrationService(UserRepository users, AccountTokenService accountTokens,
                               PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder,
                               ApplicationEventPublisher events, TransactionOperations transactions,
                               AuthProperties properties, Clock clock) {
        this.users = users;
        this.accountTokens = accountTokens;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * <b>Why a programmatic transaction here?</b> Argon2 hashing takes tens of milliseconds of
     * CPU. With {@code @Transactional} on the whole method, a database connection would sit idle
     * from the pool during hashing. Hashing first and opening the transaction afterwards keeps
     * connections free for other requests.
     *
     * <p>The password is hashed even when the email already exists, so both paths cost the same
     * time and a stopwatch cannot reveal registered emails.
     */
    public void register(String rawEmail, String password) {
        String email = User.normalizeEmail(rawEmail);
        passwordPolicy.validate("password", password, email);
        String passwordHash = passwordEncoder.encode(password);

        try {
            transactions.executeWithoutResult(status -> registerInTransaction(email, passwordHash));
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // Two simultaneous registrations passed the existence check; the unique constraint
            // stopped the second. Treat it like any duplicate: same response, no error.
            log.info("Concurrent registration rejected by unique constraint: email={}", Masking.email(email));
        }
    }

    private void registerInTransaction(String email, String passwordHash) {
        Optional<User> existing = users.findByEmail(email);
        if (existing.isPresent()) {
            handleExistingAccount(existing.get());
            return;
        }
        // saveAndFlush: run the INSERT now, inside the try block above, so a unique
        // violation surfaces here rather than at commit time.
        User user = users.saveAndFlush(User.register(email, passwordHash, Instant.now(clock)));
        issueVerification(user);
        log.info("User registered: userId={}", user.getId());
    }

    private void handleExistingAccount(User user) {
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            // Most likely the same person who lost the first email: send a fresh link.
            issueVerification(user);
            log.info("Registration repeated for unverified account; new link issued: userId={}", user.getId());
        } else {
            events.publishEvent(new DuplicateRegistrationAttempted(user.getEmail()));
            log.info("Registration attempted for existing account: userId={}", user.getId());
        }
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        User user = accountTokens.consume(rawToken, TokenPurpose.EMAIL_VERIFICATION);
        if (user.getStatus() != UserStatus.PENDING_VERIFICATION) {
            // e.g. deactivated meanwhile. Throwing rolls back, so the token is not consumed.
            throw AccountTokenService.invalidToken();
        }
        user.verifyEmail(Instant.now(clock));
        log.info("Email verified: userId={}", user.getId());
    }

    public void resendVerification(String rawEmail) {
        String email = User.normalizeEmail(rawEmail);
        transactions.executeWithoutResult(status -> users.findByEmail(email)
                .filter(user -> user.getStatus() == UserStatus.PENDING_VERIFICATION)
                .ifPresentOrElse(
                        this::issueVerification,
                        () -> log.debug("Resend ignored: no pending account for email={}", Masking.email(email))));
    }

    private void issueVerification(User user) {
        String rawToken = accountTokens.issue(user, TokenPurpose.EMAIL_VERIFICATION, properties.emailVerificationTtl());
        events.publishEvent(new VerificationEmailRequested(user.getEmail(), rawToken));
    }
}
