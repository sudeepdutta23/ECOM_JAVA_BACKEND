package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.RefreshToken;
import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.event.RefreshTokenReuseDetected;
import com.ecommerce.backend.auth.repository.RefreshTokenRepository;
import com.ecommerce.backend.auth.security.OpaqueTokens;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.util.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Lifecycle of refresh tokens: issue, rotate, revoke.
 *
 * <p><b>Rotation:</b> every refresh consumes the presented token and returns a new one in the same
 * family. A refresh token is therefore usable exactly once.
 *
 * <p><b>Reuse detection:</b> presenting a token that was already rotated means two parties hold
 * copies of it. The whole family is revoked, which signs out both the thief and the victim on
 * that device, and the owner is emailed.
 *
 * <p><b>Known UX trade-off:</b> two browser tabs refreshing at the same instant look exactly like
 * reuse, and the second one ends the session. Clients must refresh "single-flight" (one refresh
 * in progress, the others wait for its result). Some systems add a few seconds of grace instead,
 * at the price of a small replay window.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository tokens;
    private final OpaqueTokens opaqueTokens;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transactions;
    private final AuthProperties properties;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, OpaqueTokens opaqueTokens,
                               ApplicationEventPublisher events, TransactionOperations transactions,
                               AuthProperties properties, Clock clock) {
        this.tokens = tokens;
        this.opaqueTokens = opaqueTokens;
        this.events = events;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /** Result of a successful rotation: who the token belongs to, and its replacement. */
    public record Rotation(UUID userId, IssuedRefreshToken refreshToken) {
    }

    // ------------------------------------------------------------------ issue

    /**
     * Starts a new token family for a fresh login. Every later rotation of this token stays in
     * the same family, so reuse of any old member can revoke the whole chain.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedRefreshToken issueForNewLogin(User user, ClientInfo client) {
        return issue(user, UuidV7.generate(), client, Instant.now(clock)).issued();
    }

    // ------------------------------------------------------------------ rotate

    /**
     * Exchanges a refresh token for a new one.
     *
     * <p>The work runs in a programmatic transaction that returns {@code null} on rejection
     * instead of throwing. Throwing inside would roll back, and the family revocation done for
     * reuse detection would be lost. The exception is thrown only after the commit.
     */
    public Rotation rotate(String rawToken, ClientInfo client) {
        if (rawToken == null || !rawToken.matches(OpaqueTokens.FORMAT_REGEX)) {
            log.info("Refresh rejected: reason=MISSING_OR_MALFORMED");
            throw invalidRefreshToken();
        }
        Instant now = Instant.now(clock);
        Rotation rotation = transactions.execute(status -> rotateInTransaction(rawToken, client, now));
        if (rotation == null) {
            throw invalidRefreshToken();
        }
        return rotation;
    }

    private Rotation rotateInTransaction(String rawToken, ClientInfo client, Instant now) {
        // Row lock: a concurrent refresh with the same token waits here, then sees it rotated.
        Optional<RefreshToken> found = tokens.findByTokenHashForUpdate(OpaqueTokens.hash(rawToken));
        if (found.isEmpty()) {
            log.info("Refresh rejected: reason=UNKNOWN_TOKEN");
            return null;
        }
        RefreshToken token = found.get();
        User user = token.getUser();

        if (token.isRevoked()) {
            handleRevokedTokenPresented(token, user, now);
            return null;
        }
        if (!token.isActiveAt(now)) {
            log.info("Refresh rejected: reason=EXPIRED, userId={}", user.getId());
            return null;
        }
        if (!user.isActive()) {
            token.revoke(RevokeReason.ACCOUNT_DEACTIVATED, now);
            log.info("Refresh rejected: reason=ACCOUNT_NOT_ACTIVE, userId={}", user.getId());
            return null;
        }

        Issued successor = issue(user, token.getFamilyId(), client, now);
        // The successor row is inserted before this update at flush time, so the
        // replaced_by_id foreign key is satisfied.
        token.markRotated(successor.entity().getId(), now);
        log.info("Refresh token rotated: userId={}, familyId={}", user.getId(), token.getFamilyId());
        return new Rotation(user.getId(), successor.issued());
    }

    private void handleRevokedTokenPresented(RefreshToken token, User user, Instant now) {
        RevokeReason originalReason = token.getRevokeReason();
        UUID familyId = token.getFamilyId();
        // Read everything needed BEFORE the bulk update: it clears the persistence context,
        // after which lazy associations of these entities can no longer be loaded.
        String email = user.getEmail();
        UUID userId = user.getId();

        int revoked = tokens.revokeAllActiveByFamilyId(familyId, RevokeReason.REUSE_DETECTED, now);

        if (originalReason == RevokeReason.ROTATED && revoked > 0) {
            log.warn("Refresh token REUSE detected; session family revoked: userId={}, familyId={}",
                    userId, familyId);
            events.publishEvent(new RefreshTokenReuseDetected(email, familyId));
        } else {
            // Already-dead family (logout, or a second replay): nothing new to report.
            log.info("Refresh rejected: reason=REVOKED_{}, userId={}", originalReason, userId);
        }
    }

    // ------------------------------------------------------------------ revoke

    /** Logout of one session. Idempotent and silent: unknown or dead tokens are ignored. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || !rawToken.matches(OpaqueTokens.FORMAT_REGEX)) {
            return;
        }
        tokens.findByTokenHash(OpaqueTokens.hash(rawToken))
                .filter(token -> !token.isRevoked())
                .ifPresent(token -> {
                    token.revoke(RevokeReason.LOGOUT, Instant.now(clock));
                    log.info("Logged out: userId={}, familyId={}", token.getUser().getId(), token.getFamilyId());
                });
    }

    /** Ends every session of a user: logout-all now, password change and deactivation later. */
    @Transactional
    public int revokeAll(UUID userId, RevokeReason reason) {
        int revoked = tokens.revokeAllActiveByUserId(userId, reason, Instant.now(clock));
        log.info("All sessions revoked: userId={}, reason={}, count={}", userId, reason, revoked);
        return revoked;
    }

    // ------------------------------------------------------------------ helpers

    private record Issued(RefreshToken entity, IssuedRefreshToken issued) {
    }

    private Issued issue(User user, UUID familyId, ClientInfo client, Instant now) {
        OpaqueTokens.Generated generated = opaqueTokens.generate();
        RefreshToken token = tokens.save(RefreshToken.issue(user, familyId, generated.hash(), now,
                properties.refreshTokenTtl(), client.ipAddress(), client.userAgent()));
        return new Issued(token, new IssuedRefreshToken(generated.raw(), token.getExpiresAt()));
    }

    static BusinessException invalidRefreshToken() {
        return new BusinessException(ErrorCode.AUTH_INVALID_REFRESH_TOKEN,
                "The session has expired or is no longer valid. Please sign in again.");
    }
}
