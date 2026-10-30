package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.auth.security.AccessToken;
import com.ecommerce.backend.auth.security.AccessTokenIssuer;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.util.Masking;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Email + password login with failed-attempt tracking and temporary lockout.
 *
 * <p><b>Order of checks, and why</b>
 * <ol>
 *   <li>Unknown email: verify the password against a dummy hash anyway, so the response takes as
 *       long as for a real account.</li>
 *   <li>Locked account: do NOT check the real password, otherwise the lock would not stop
 *       guessing. Answer exactly like a wrong password.</li>
 *   <li>Wrong password: count the failure atomically (and lock at the threshold), committed in
 *       its own transaction BEFORE throwing, so the count survives the 401.</li>
 *   <li>Correct password, but unverified or deactivated: 403 with a specific code. Revealing the
 *       state is fine here because the caller has just proven they know the password.</li>
 *   <li>Success: reset the counter, upgrade an outdated hash, issue tokens.</li>
 * </ol>
 *
 * <p>Steps 1 to 3 all return the same {@code AUTH_INVALID_CREDENTIALS} body.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final AccessTokenIssuer accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final FailedLoginRecorder failedLogins;
    private final TransactionOperations transactions;
    private final Duration failedLoginMinDuration;
    private final Clock clock;
    /** A real hash of a random string; checking against it costs the same as a real check. */
    private final String dummyHash;

    public AuthenticationService(UserRepository users, RefreshTokenService refreshTokens,
                                 AccessTokenIssuer accessTokens, PasswordEncoder passwordEncoder,
                                 FailedLoginRecorder failedLogins, TransactionOperations transactions,
                                 AuthProperties properties, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.failedLogins = failedLogins;
        this.transactions = transactions;
        this.failedLoginMinDuration = properties.failedLoginMinDuration();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Logs in, with a response-time floor on failure.
     *
     * <p><b>Why a floor?</b> Measured locally: a wrong password on a real account took ~45 ms
     * longer than an unknown email, because it also writes the failure counter. Averaging many
     * attempts, an attacker could use that gap to discover registered emails despite identical
     * response bodies. Padding every invalid-credentials failure to a fixed minimum hides which
     * path ran. Successful logins are not padded: the caller already knows the password.
     */
    public LoginResult login(String rawEmail, String password, ClientInfo client) {
        long startedNanos = System.nanoTime();
        try {
            return attemptLogin(rawEmail, password, client);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.AUTH_INVALID_CREDENTIALS) {
                padUntil(startedNanos + failedLoginMinDuration.toNanos());
            }
            throw e;
        }
    }

    private static void padUntil(long deadlineNanos) {
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
        if (remainingMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(remainingMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private LoginResult attemptLogin(String rawEmail, String password, ClientInfo client) {
        String email = User.normalizeEmail(rawEmail);
        Instant now = Instant.now(clock);
        Optional<User> found = users.findByEmail(email);

        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            log.info("Login failed: reason=UNKNOWN_EMAIL, email={}", Masking.email(email));
            throw invalidCredentials();
        }
        User user = found.get();

        if (user.isLockedAt(now)) {
            passwordEncoder.matches(password, dummyHash);
            log.info("Login rejected: reason=LOCKED, userId={}, lockedUntil={}", user.getId(), user.getLockedUntil());
            throw invalidCredentials();
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            recordFailure(user, now);
            throw invalidCredentials();
        }

        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            log.info("Login rejected: reason=EMAIL_NOT_VERIFIED, userId={}", user.getId());
            throw new BusinessException(ErrorCode.AUTH_EMAIL_NOT_VERIFIED,
                    "Verify your email address before signing in. You can request a new link.");
        }
        if (user.getStatus() == UserStatus.DEACTIVATED) {
            log.info("Login rejected: reason=DEACTIVATED, userId={}", user.getId());
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED, "This account has been deactivated.");
        }

        return succeed(user, password, client, now);
    }

    private void recordFailure(User user, Instant now) {
        failedLogins.record(user, now);
        log.info("Login failed: reason=BAD_PASSWORD, userId={}", user.getId());
    }

    private LoginResult succeed(User user, String password, ClientInfo client, Instant now) {
        // Hash outside the transaction, like registration does.
        String upgradedHash = passwordEncoder.upgradeEncoding(user.getPasswordHash())
                ? passwordEncoder.encode(password)
                : null;

        IssuedRefreshToken refresh = transactions.execute(status -> {
            users.recordSuccessfulLogin(user.getId(), now);
            if (upgradedHash != null) {
                users.upgradePasswordHash(user.getId(), upgradedHash, now);
                log.info("Password hash upgraded to current algorithm: userId={}", user.getId());
            }
            // A reference (proxy) is enough to set the foreign key; no extra SELECT.
            return refreshTokens.issueForNewLogin(users.getReferenceById(user.getId()), client);
        });

        AccessToken access = accessTokens.issue(user.getId());
        log.info("Login succeeded: userId={}", user.getId());
        return new LoginResult(access, refresh);
    }

    // ------------------------------------------------------------------ sessions

    /** Exchanges a refresh token for a new access token and a new refresh token. */
    public LoginResult refresh(String rawRefreshToken, ClientInfo client) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken, client);
        return new LoginResult(accessTokens.issue(rotation.userId()), rotation.refreshToken());
    }

    /** Ends the session the refresh token belongs to. Always succeeds from the caller's view. */
    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }

    /**
     * Ends every session of the user. Access tokens already issued stay valid until they expire
     * (at most 15 minutes), because they are verified without a database lookup (ADR-003).
     */
    public void logoutAll(UUID userId) {
        refreshTokens.revokeAll(userId, RevokeReason.LOGOUT_ALL);
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid email or password.");
    }
}
