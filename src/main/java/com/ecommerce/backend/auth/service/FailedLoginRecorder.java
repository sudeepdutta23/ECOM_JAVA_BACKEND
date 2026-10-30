package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.event.AccountLocked;
import com.ecommerce.backend.auth.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Counts a wrong password against an account and locks it at the threshold.
 *
 * <p>Shared by login, password change and self-deactivation. Otherwise an attacker holding a
 * stolen access token could guess the current password without limit through the
 * change-password endpoint.
 *
 * <p>Runs in its own committed transaction so the count survives the error response that
 * follows.
 */
@Component
class FailedLoginRecorder {

    private static final Logger log = LoggerFactory.getLogger(FailedLoginRecorder.class);

    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transactions;
    private final AuthProperties properties;

    FailedLoginRecorder(UserRepository users, ApplicationEventPublisher events,
                        TransactionOperations transactions, AuthProperties properties) {
        this.users = users;
        this.events = events;
        this.transactions = transactions;
        this.properties = properties;
    }

    void record(User user, Instant now) {
        // PostgreSQL stores microseconds; truncating lets us compare our value with the stored one.
        Instant lockUntil = now.plus(properties.lockoutDuration()).truncatedTo(ChronoUnit.MICROS);
        transactions.executeWithoutResult(status -> {
            users.registerFailedLogin(user.getId(), properties.maxFailedLogins(), lockUntil, now);
            // Re-read after the atomic update. If the stored lock time is exactly ours, THIS
            // request crossed the threshold, so exactly one lock email goes out even under
            // parallel attempts.
            users.findById(user.getId())
                    .filter(fresh -> lockUntil.equals(fresh.getLockedUntil()))
                    .ifPresent(fresh -> {
                        log.warn("Account locked after repeated failures: userId={}, lockedUntil={}",
                                fresh.getId(), lockUntil);
                        events.publishEvent(new AccountLocked(fresh.getEmail(), lockUntil));
                    });
        });
    }
}
