package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.TokenPurpose;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.event.PasswordChanged;
import com.ecommerce.backend.auth.event.PasswordResetRequested;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.auth.security.AccessTokenIssuer;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.util.Masking;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Change, forgot and reset password.
 *
 * <p>Every successful password change or reset revokes ALL refresh tokens of the user. A common
 * reason to change a password is suspecting someone else knows it, and that person may already
 * hold a session. The owner is emailed every time.
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final UserRepository users;
    private final CurrentPasswordCheck currentPassword;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final AccountTokenService accountTokens;
    private final AccessTokenIssuer accessTokens;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transactions;
    private final AuthProperties properties;
    private final Clock clock;

    public PasswordService(UserRepository users, CurrentPasswordCheck currentPassword, PasswordPolicy passwordPolicy,
                           PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens,
                           AccountTokenService accountTokens, AccessTokenIssuer accessTokens,
                           ApplicationEventPublisher events, TransactionOperations transactions,
                           AuthProperties properties, Clock clock) {
        this.users = users;
        this.currentPassword = currentPassword;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.accountTokens = accountTokens;
        this.accessTokens = accessTokens;
        this.events = events;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ change (signed in)

    /**
     * Changes the password, ends every other session, and returns a fresh session for the
     * device that made the change so the user is not signed out of it.
     */
    public LoginResult changePassword(UUID userId, String oldPassword, String newPassword, ClientInfo client) {
        Instant now = Instant.now(clock);
        User user = users.findById(userId).filter(User::isActive).orElseThrow(AccountService::accountUnavailable);

        currentPassword.verify(user, oldPassword, now);
        passwordPolicy.validate("newPassword", newPassword, user.getEmail());
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw RequestValidationException.of("newPassword", "must be different from the current password");
        }
        String newHash = passwordEncoder.encode(newPassword); // slow; outside the transaction

        IssuedRefreshToken refresh = transactions.execute(status -> {
            User managed = users.findById(userId).orElseThrow(AccountService::accountUnavailable);
            managed.changePassword(newHash, now);
            String email = managed.getEmail(); // read before the bulk revoke clears the context
            refreshTokens.revokeAll(userId, RevokeReason.PASSWORD_CHANGED);
            events.publishEvent(new PasswordChanged(email));
            return refreshTokens.issueForNewLogin(users.getReferenceById(userId), client);
        });

        log.info("Password changed: userId={}", userId);
        return new LoginResult(accessTokens.issue(userId), refresh);
    }

    // ------------------------------------------------------------------ forgot / reset (signed out)

    /** Always looks the same to the caller; only ACTIVE accounts receive a link. */
    public void forgotPassword(String rawEmail) {
        String email = User.normalizeEmail(rawEmail);
        transactions.executeWithoutResult(status -> users.findByEmail(email)
                .filter(User::isActive)
                .ifPresentOrElse(user -> {
                    String rawToken = accountTokens.issue(user, TokenPurpose.PASSWORD_RESET, properties.passwordResetTtl());
                    events.publishEvent(new PasswordResetRequested(user.getEmail(), rawToken));
                    log.info("Password reset link issued: userId={}", user.getId());
                }, () -> log.debug("Password reset ignored: no active account for email={}", Masking.email(email))));
    }

    /**
     * Sets a new password with a reset token.
     *
     * <ol>
     *   <li>Peek at the token (no lock) to find the user, then validate and hash the new password
     *       outside any transaction.</li>
     *   <li>Consume the token under a row lock and apply the change. If anything fails here the
     *       transaction rolls back and the token stays usable, so a policy error does not burn
     *       the link.</li>
     * </ol>
     *
     * <p>A successful reset also clears any lockout: the user just proved they own the email.
     */
    public void resetPassword(String rawToken, String newPassword) {
        User owner = accountTokens.peekUser(rawToken, TokenPurpose.PASSWORD_RESET);
        passwordPolicy.validate("newPassword", newPassword, owner.getEmail());
        String newHash = passwordEncoder.encode(newPassword);
        Instant now = Instant.now(clock);

        transactions.executeWithoutResult(status -> {
            User user = accountTokens.consume(rawToken, TokenPurpose.PASSWORD_RESET);
            if (!user.isActive()) {
                throw AccountTokenService.invalidToken(); // deactivated since the link was sent
            }
            user.changePassword(newHash, now);
            String email = user.getEmail();
            UUID userId = user.getId();
            refreshTokens.revokeAll(userId, RevokeReason.PASSWORD_RESET);
            events.publishEvent(new PasswordChanged(email));
            log.info("Password reset completed: userId={}", userId);
        });
    }
}
