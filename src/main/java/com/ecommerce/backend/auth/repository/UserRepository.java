package com.ecommerce.backend.auth.repository;

import com.ecommerce.backend.auth.entity.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Callers must pass an already-normalized email ({@link User#normalizeEmail}). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** DTO projection: a constructor expression selects only the listed columns. */
    @Query("""
            select new com.ecommerce.backend.auth.repository.UserSummary(
                u.id, u.email, u.status, u.emailVerifiedAt, u.lastLoginAt, u.createdAt)
            from User u where u.id = :id
            """)
    Optional<UserSummary> findSummaryById(@Param("id") UUID id);

    /**
     * Records one failed login atomically and locks the account when the threshold is reached.
     *
     * <p><b>Why SQL instead of {@code user.setFailedLoginCount(n + 1)}?</b> Read-modify-write
     * loses updates: two parallel wrong passwords both read 3 and both write 4. A single
     * {@code UPDATE ... SET count = count + 1} is atomic in PostgreSQL, so an attacker cannot
     * get extra guesses by sending requests in parallel.
     *
     * <p>If a previous lock has already expired, counting restarts at 1, so one typo after a
     * lockout does not lock the account again immediately.
     *
     * <p>{@code clearAutomatically} evicts entities from the persistence context, because a
     * bulk update bypasses it and would leave a stale {@code User} in memory.
     *
     * @return number of rows updated: 1, or 0 if the user does not exist
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE users SET
                failed_login_count = CASE WHEN locked_until <= :now THEN 1
                                          ELSE failed_login_count + 1 END,
                locked_until = CASE
                    WHEN (CASE WHEN locked_until <= :now THEN 1 ELSE failed_login_count + 1 END) >= :maxAttempts
                        THEN :lockUntil
                    WHEN locked_until <= :now THEN NULL
                    ELSE locked_until END,
                updated_at = :now
            WHERE id = :id
            """, nativeQuery = true)
    int registerFailedLogin(@Param("id") UUID id,
                            @Param("maxAttempts") int maxAttempts,
                            @Param("lockUntil") Instant lockUntil,
                            @Param("now") Instant now);

    /**
     * Clears lockout state after a correct password, as one atomic UPDATE.
     *
     * <p>Login is the hottest write path in the system. Loading the entity and saving it would
     * bump the optimistic-lock version, so two logins from two devices at once could fail with a
     * version conflict. These columns have no invariant that needs that protection.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update User u set u.failedLoginCount = 0, u.lockedUntil = null,
                              u.lastLoginAt = :now, u.updatedAt = :now
            where u.id = :id
            """)
    int recordSuccessfulLogin(@Param("id") UUID id, @Param("now") Instant now);

    /** Replaces an outdated hash (old algorithm or parameters) after a successful login. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.passwordHash = :hash, u.updatedAt = :now where u.id = :id")
    int upgradePasswordHash(@Param("id") UUID id, @Param("hash") String hash, @Param("now") Instant now);
}
