package com.ecommerce.backend.auth.repository;

import com.ecommerce.backend.auth.entity.RefreshToken;
import com.ecommerce.backend.auth.entity.RevokeReason;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Loads a token and locks its row ({@code SELECT ... FOR UPDATE}) until the transaction ends.
     *
     * <p><b>Why pessimistic here?</b> Two requests may refresh with the same token at the same
     * moment (a double-click, or an attacker racing the real user). Without a lock both could
     * see "not revoked" and both receive a new token. With the lock the second request waits,
     * then sees the token is already rotated and triggers reuse detection.
     * Optimistic locking would also detect it, but only by failing late with an exception.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * The current status of the token's owner, with a share lock on the owner's {@code users} row
     * until the transaction ends. Empty if no token has this hash.
     *
     * <p>Refresh takes this lock BEFORE {@link #findByTokenHashForUpdate}. Account-wide changes
     * (suspend, erase, password change) update the user row first and touch the tokens second, so
     * both sides lock in the same order and cannot deadlock. With the lock:
     * <ul>
     *   <li>a change that committed first is seen here (READ COMMITTED reads after the lock);</li>
     *   <li>a change that starts later waits until this refresh commits, and then its token
     *       statement sees the successor token. Erasure cannot miss a token, and with it an IP
     *       address and user agent.</li>
     * </ul>
     * A scalar query, so no entity enters the persistence context with possibly stale state.
     */
    @Query(value = """
            SELECT u.status FROM users u
            WHERE u.id = (SELECT t.user_id FROM refresh_tokens t WHERE t.token_hash = :tokenHash)
            FOR SHARE OF u
            """, nativeQuery = true)
    Optional<String> lockOwnerStatusByTokenHash(@Param("tokenHash") String tokenHash);

    /** Logout-all, password change and deactivation: revoke every live token of a user. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason
            where t.user.id = :userId and t.revokedAt is null
            """)
    int revokeAllActiveByUserId(@Param("userId") UUID userId,
                                @Param("reason") RevokeReason reason,
                                @Param("now") Instant now);

    /** Reuse detection: revoke every live token that descends from the same login. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason
            where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeAllActiveByFamilyId(@Param("familyId") UUID familyId,
                                  @Param("reason") RevokeReason reason,
                                  @Param("now") Instant now);

    long countByUser_IdAndRevokedAtIsNull(UUID userId);

    /** Active sessions: rotation keeps exactly one live token per family. Served by {@code idx_refresh_tokens_user_id}. */
    long countByUser_IdAndRevokedAtIsNullAndExpiresAtAfter(UUID userId, Instant now);

    /** One signed-in device, for {@code GET /me/sessions}. Aliases are quoted: PostgreSQL lower-cases unquoted ones. */
    interface SessionRow {
        UUID getSessionId();

        Instant getStartedAt();

        Instant getLastActiveAt();

        Instant getExpiresAt();

        String getIp();

        String getUserAgent();
    }

    /**
     * A user's live sessions, newest activity first, in one query.
     *
     * <p>Rotation leaves exactly one live token per family, so each live token is one session. Its
     * own {@code issued_at}, IP and user agent describe the LAST refresh; the family's oldest
     * token gives the sign-in time. Both parts read {@code idx_refresh_tokens_user_id}.
     */
    @Query(value = """
            SELECT t.family_id AS "sessionId", f.started_at AS "startedAt", t.issued_at AS "lastActiveAt",
                   t.expires_at AS "expiresAt", t.created_ip AS "ip", t.user_agent AS "userAgent"
            FROM refresh_tokens t
            JOIN (SELECT family_id, min(issued_at) AS started_at
                  FROM refresh_tokens WHERE user_id = :userId GROUP BY family_id) f ON f.family_id = t.family_id
            WHERE t.user_id = :userId AND t.revoked_at IS NULL AND t.expires_at > :now
            ORDER BY t.issued_at DESC, t.family_id
            """, nativeQuery = true)
    List<SessionRow> findActiveSessions(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Ownership check before ending a session: a family ID alone proves nothing. */
    boolean existsByFamilyIdAndUser_Id(UUID familyId, UUID userId);

    /**
     * Erasure: deletes rather than revokes, because every row holds an IP address and a user agent,
     * which are personal data. Served by {@code idx_refresh_tokens_user_id}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshToken t where t.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
