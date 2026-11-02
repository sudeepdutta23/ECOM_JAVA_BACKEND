package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.dto.SessionResponse;
import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.repository.RefreshTokenRepository;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user's own sessions: one per sign-in on a device (a refresh-token family).
 *
 * <p>Ending a session revokes its family, so that device can no longer refresh. Its current access
 * token stays valid until it expires (at most 15 minutes), like after a normal logout.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final RefreshTokenRepository tokens;
    private final Clock clock;

    public SessionService(RefreshTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /** @param currentSessionId the caller's {@code sid} claim; null for tokens issued before it existed */
    @Transactional(readOnly = true)
    public List<SessionResponse> list(UUID userId, UUID currentSessionId) {
        return tokens.findActiveSessions(userId, Instant.now(clock)).stream()
                .map(row -> SessionResponse.from(row, currentSessionId))
                .toList();
    }

    /**
     * Ends one of the caller's sessions. Idempotent for the owner: ending an already ended session
     * succeeds. A session of another user, or an unknown ID, is 404, never 403, so the endpoint does
     * not confirm that someone else's session exists.
     */
    @Transactional
    public void end(UUID userId, UUID sessionId) {
        if (!tokens.existsByFamilyIdAndUser_Id(sessionId, userId)) {
            throw new ResourceNotFoundException("Session");
        }
        int revoked = tokens.revokeAllActiveByFamilyId(sessionId, RevokeReason.LOGOUT, Instant.now(clock));
        log.info("Session ended by owner: userId={}, sessionId={}, tokensRevoked={}", userId, sessionId, revoked);
    }
}
