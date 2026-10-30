package com.ecommerce.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class UserRepositoryIT extends AbstractAuthRepositoryIT {

    private static final int MAX_ATTEMPTS = 5;
    private static final Instant LOCK_UNTIL = NOW.plus(Duration.ofMinutes(15));

    @Test
    void saveAssignsUuidV7VersionAndAuditTimestamps() {
        User user = users.save(User.register("  Mixed.Case-" + UUID.randomUUID() + "@REPO.TEST ", "{argon2}h", NOW));

        assertThat(user.getId()).isNotNull();
        assertThat(user.getId().version()).isEqualTo(7);
        assertThat(user.getVersion()).isZero();
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
        assertThat(user.getEmail()).startsWith("mixed.case-").endsWith("@repo.test");
    }

    @Test
    void findByEmailReturnsTheUser() {
        User saved = savedUser();

        assertThat(users.findByEmail(saved.getEmail())).get()
                .extracting(User::getId, User::getStatus)
                .containsExactly(saved.getId(), UserStatus.PENDING_VERIFICATION);
        assertThat(users.existsByEmail(saved.getEmail())).isTrue();
        assertThat(users.findByEmail("nobody@repo.test")).isEmpty();
    }

    @Test
    void duplicateEmailViolatesUniqueConstraint() {
        User first = savedUser();

        assertThatThrownBy(() -> users.saveAndFlush(User.register(first.getEmail(), "{argon2}h", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleUpdateIsRejectedByOptimisticLocking() {
        UUID id = savedUser().getId();
        // Two independent reads, like two concurrent HTTP requests.
        User first = users.findById(id).orElseThrow();
        User second = users.findById(id).orElseThrow();

        first.deactivate();
        users.save(first); // version 0 -> 1

        second.changePassword("{argon2}other", NOW);
        assertThatThrownBy(() -> users.save(second)) // still believes version is 0
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void failedLoginsIncrementUntilTheAccountLocks() {
        UUID id = savedUser().getId();

        for (int i = 1; i < MAX_ATTEMPTS; i++) {
            registerFailure(id, NOW);
        }
        User beforeLimit = users.findById(id).orElseThrow();
        registerFailure(id, NOW);
        User atLimit = users.findById(id).orElseThrow();

        assertThat(beforeLimit.getFailedLoginCount()).isEqualTo(4);
        assertThat(beforeLimit.getLockedUntil()).isNull();
        assertThat(atLimit.getFailedLoginCount()).isEqualTo(5);
        assertThat(atLimit.getLockedUntil()).isEqualTo(LOCK_UNTIL);
        assertThat(atLimit.isLockedAt(NOW)).isTrue();
    }

    @Test
    void failureAfterExpiredLockRestartsCounting() {
        UUID id = savedUser().getId();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            registerFailure(id, NOW);
        }

        Instant afterLock = LOCK_UNTIL.plusSeconds(1);
        tx.executeWithoutResult(s ->
                users.registerFailedLogin(id, MAX_ATTEMPTS, afterLock.plus(Duration.ofMinutes(15)), afterLock));
        User user = users.findById(id).orElseThrow();

        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void failedLoginForUnknownUserUpdatesNothing() {
        Integer updated = tx.execute(s -> users.registerFailedLogin(UUID.randomUUID(), MAX_ATTEMPTS, LOCK_UNTIL, NOW));

        assertThat(updated).isZero();
    }

    @Test
    void concurrentFailedLoginsAreNeverLost() throws Exception {
        UUID id = savedUser().getId();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                // High threshold so locking does not interfere with counting.
                return tx.execute(s -> users.registerFailedLogin(id, 1_000, LOCK_UNTIL, NOW));
            }));
        }
        startGate.countDown(); // release all threads at once
        for (Future<?> result : results) {
            result.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(users.findById(id).orElseThrow().getFailedLoginCount()).isEqualTo(threads);
    }

    private void registerFailure(UUID id, Instant now) {
        tx.executeWithoutResult(s -> users.registerFailedLogin(id, MAX_ATTEMPTS, LOCK_UNTIL, now));
    }
}
