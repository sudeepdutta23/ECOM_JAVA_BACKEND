package com.ecommerce.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.auth.entity.RefreshToken;
import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.User;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class RefreshTokenRepositoryIT extends AbstractAuthRepositoryIT {

    private static final Duration TTL = Duration.ofDays(7);

    @Autowired
    private RefreshTokenRepository tokens;

    private RefreshToken savedToken(User user, UUID familyId, String hash) {
        return tokens.save(RefreshToken.issue(user, familyId, hash, NOW, TTL, "127.0.0.1", "JUnit"));
    }

    @Test
    void findByTokenHashReturnsTokenWithLazyUserId() {
        User user = savedUser();
        String hash = randomHash();
        savedToken(user, UUID.randomUUID(), hash);

        RefreshToken found = tokens.findByTokenHash(hash).orElseThrow();

        assertThat(found.getId().version()).isEqualTo(7);
        // The proxy knows its ID without loading the users row, even outside a transaction.
        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getExpiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void revokeAllActiveByUserIdTouchesOnlyThatUsersLiveTokens() {
        User alice = savedUser();
        User bob = savedUser();
        savedToken(alice, UUID.randomUUID(), randomHash());
        savedToken(alice, UUID.randomUUID(), randomHash());
        RefreshToken alreadyRevoked = savedToken(alice, UUID.randomUUID(), randomHash());
        tx.executeWithoutResult(s -> tokens.findById(alreadyRevoked.getId()).orElseThrow()
                .revoke(RevokeReason.LOGOUT, NOW));
        savedToken(bob, UUID.randomUUID(), randomHash());

        Integer revoked = tx.execute(s -> tokens.revokeAllActiveByUserId(alice.getId(), RevokeReason.LOGOUT_ALL, NOW));

        assertThat(revoked).isEqualTo(2);
        assertThat(tokens.countByUser_IdAndRevokedAtIsNull(alice.getId())).isZero();
        assertThat(tokens.countByUser_IdAndRevokedAtIsNull(bob.getId())).isEqualTo(1);
        assertThat(tokens.findById(alreadyRevoked.getId()).orElseThrow().getRevokeReason())
                .isEqualTo(RevokeReason.LOGOUT); // original reason preserved
    }

    @Test
    void revokeAllActiveByFamilyIdLeavesOtherFamiliesAlone() {
        User user = savedUser();
        UUID stolenFamily = UUID.randomUUID();
        UUID otherDevice = UUID.randomUUID();
        savedToken(user, stolenFamily, randomHash());
        savedToken(user, stolenFamily, randomHash());
        savedToken(user, otherDevice, randomHash());

        Integer revoked = tx.execute(s ->
                tokens.revokeAllActiveByFamilyId(stolenFamily, RevokeReason.REUSE_DETECTED, NOW));

        assertThat(revoked).isEqualTo(2);
        assertThat(tokens.countByUser_IdAndRevokedAtIsNull(user.getId())).isEqualTo(1);
    }

    @Test
    void rotationChangesArePersistedByDirtyChecking() {
        User user = savedUser();
        String hash = randomHash();
        UUID familyId = UUID.randomUUID();
        RefreshToken original = savedToken(user, familyId, hash);
        // The successor must exist: fk_refresh_tokens_replaced_by rejects links to unknown tokens.
        UUID successorId = savedToken(user, familyId, randomHash()).getId();

        // No explicit save(): a managed entity changed inside a transaction is flushed on commit.
        tx.executeWithoutResult(s -> tokens.findByTokenHashForUpdate(hash).orElseThrow()
                .markRotated(successorId, NOW));

        RefreshToken reloaded = tokens.findById(original.getId()).orElseThrow();
        assertThat(reloaded.getRevokeReason()).isEqualTo(RevokeReason.ROTATED);
        assertThat(reloaded.getReplacedById()).isEqualTo(successorId);
        assertThat(reloaded.getRevokedAt()).isEqualTo(NOW);
    }

    @Test
    void findForUpdateHoldsARowLockUntilCommit() throws Exception {
        User user = savedUser();
        String hash = randomHash();
        savedToken(user, UUID.randomUUID(), hash);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            tokens.findByTokenHashForUpdate(hash).orElseThrow();
            locked.countDown();
            awaitQuietly(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // A second transaction asking for the same row lock without waiting must fail.
        assertThatThrownBy(() -> jdbc.queryForList(
                "SELECT id FROM refresh_tokens WHERE token_hash = ? FOR UPDATE NOWAIT", hash))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("lock");

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        pool.shutdown();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
