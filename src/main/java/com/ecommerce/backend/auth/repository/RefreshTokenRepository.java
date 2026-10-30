package com.ecommerce.backend.auth.repository;

import com.ecommerce.backend.auth.entity.RefreshToken;
import com.ecommerce.backend.auth.entity.RevokeReason;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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
}
