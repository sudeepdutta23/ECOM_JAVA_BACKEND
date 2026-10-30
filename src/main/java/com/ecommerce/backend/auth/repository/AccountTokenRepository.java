package com.ecommerce.backend.auth.repository;

import com.ecommerce.backend.auth.entity.AccountToken;
import com.ecommerce.backend.auth.entity.TokenPurpose;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountTokenRepository extends JpaRepository<AccountToken, UUID> {

    /** Read without locking; for a peek before the real, locked consume. */
    Optional<AccountToken> findByTokenHash(String tokenHash);

    /** Locks the row so a token clicked twice at once is consumed exactly once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AccountToken t where t.tokenHash = :tokenHash")
    Optional<AccountToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * Invalidates all unused tokens of one purpose for a user. Called before issuing a new one,
     * so only the most recent email link works.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AccountToken t set t.consumedAt = :now
            where t.user.id = :userId and t.purpose = :purpose and t.consumedAt is null
            """)
    int consumeAllActive(@Param("userId") UUID userId,
                         @Param("purpose") TokenPurpose purpose,
                         @Param("now") Instant now);
}
