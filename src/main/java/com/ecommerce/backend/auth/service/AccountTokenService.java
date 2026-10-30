package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.AccountToken;
import com.ecommerce.backend.auth.entity.TokenPurpose;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.repository.AccountTokenRepository;
import com.ecommerce.backend.auth.security.OpaqueTokens;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems one-time account tokens (email verification now, password reset later).
 *
 * <p>{@code Propagation.MANDATORY}: these methods never start their own transaction. They must
 * run inside the caller's, so "consume token + activate user" commits or rolls back together.
 * Calling them without a transaction fails immediately instead of silently auto-committing.
 */
@Service
public class AccountTokenService {

    private final AccountTokenRepository tokens;
    private final OpaqueTokens opaqueTokens;
    private final Clock clock;

    public AccountTokenService(AccountTokenRepository tokens, OpaqueTokens opaqueTokens, Clock clock) {
        this.tokens = tokens;
        this.opaqueTokens = opaqueTokens;
        this.clock = clock;
    }

    /**
     * Invalidates the user's earlier tokens of this purpose, then creates a new one.
     *
     * @return the RAW token, to be put in the email; only its hash is stored
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(User user, TokenPurpose purpose, Duration ttl) {
        Instant now = Instant.now(clock);
        tokens.consumeAllActive(user.getId(), purpose, now);
        OpaqueTokens.Generated generated = opaqueTokens.generate();
        tokens.save(AccountToken.issue(user, purpose, generated.hash(), now, ttl));
        return generated.raw();
    }

    /**
     * Redeems a token and returns its user.
     *
     * <p>Unknown, expired, already used and wrong-purpose tokens all produce the same error, so a
     * caller cannot tell which case happened.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public User consume(String rawToken, TokenPurpose purpose) {
        Instant now = Instant.now(clock);
        AccountToken token = tokens.findByTokenHashForUpdate(OpaqueTokens.hash(rawToken))
                .filter(t -> t.getPurpose() == purpose)
                .filter(t -> t.isUsableAt(now))
                .orElseThrow(AccountTokenService::invalidToken);
        token.consume(now);
        return token.getUser();
    }

    /**
     * Looks at a token WITHOUT consuming it, to learn whose it is.
     *
     * <p>Password reset uses this to validate the new password against the user's email and to
     * hash it before the write transaction starts. The token is then consumed under a row lock,
     * which re-checks everything, so a race between peek and consume is harmless.
     */
    @Transactional(readOnly = true)
    public User peekUser(String rawToken, TokenPurpose purpose) {
        Instant now = Instant.now(clock);
        return tokens.findByTokenHash(OpaqueTokens.hash(rawToken))
                .filter(t -> t.getPurpose() == purpose)
                .filter(t -> t.isUsableAt(now))
                .map(t -> {
                    User user = t.getUser();
                    user.getEmail(); // initialise the lazy proxy while the session is open
                    return user;
                })
                .orElseThrow(AccountTokenService::invalidToken);
    }

    static BusinessException invalidToken() {
        return new BusinessException(ErrorCode.AUTH_INVALID_ACCOUNT_TOKEN,
                "This link is invalid or has expired. Request a new one.");
    }
}
