package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.event.AccountDeactivated;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.auth.repository.UserSummary;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/** The signed-in user's own account: view it, deactivate it. */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final UserRepository users;
    private final CurrentPasswordCheck currentPassword;
    private final RefreshTokenService refreshTokens;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transactions;
    private final Clock clock;

    public AccountService(UserRepository users, CurrentPasswordCheck currentPassword,
                          RefreshTokenService refreshTokens, ApplicationEventPublisher events,
                          TransactionOperations transactions, Clock clock) {
        this.users = users;
        this.currentPassword = currentPassword;
        this.refreshTokens = refreshTokens;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * {@code readOnly = true} tells Hibernate it may skip dirty checking and lets the JDBC
     * driver and database optimise the read. It documents intent too: nothing here writes.
     */
    @Transactional(readOnly = true)
    public UserSummary currentUser(UUID userId) {
        return users.findSummaryById(userId).orElseThrow(AccountService::accountUnavailable);
    }

    /**
     * Deactivates the account after re-confirming the password, and ends every session.
     * Reactivation is an administrator action (Phase 3).
     */
    public void deactivate(UUID userId, String password) {
        Instant now = Instant.now(clock);
        User user = users.findById(userId).filter(User::isActive).orElseThrow(AccountService::accountUnavailable);
        currentPassword.verify(user, password, now);

        transactions.executeWithoutResult(status -> {
            User managed = users.findById(userId).orElseThrow(AccountService::accountUnavailable);
            managed.deactivate();
            String email = managed.getEmail();
            refreshTokens.revokeAll(userId, RevokeReason.ACCOUNT_DEACTIVATED);
            events.publishEvent(new AccountDeactivated(email));
        });
        log.info("Account deactivated by owner: userId={}", userId);
    }

    /** A valid token whose account was deleted or switched off meanwhile. */
    static BusinessException accountUnavailable() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "This account is no longer available.");
    }
}
