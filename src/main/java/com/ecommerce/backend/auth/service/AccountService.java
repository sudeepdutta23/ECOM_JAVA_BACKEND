package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.event.AccountDeactivated;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.auth.repository.UserSummary;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.org.service.MemberDeparture;
import com.ecommerce.backend.rbac.service.RoleAssignmentService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/** The signed-in user's own account: view it, deactivate it, erase it. */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final UserRepository users;
    private final CurrentPasswordCheck currentPassword;
    private final RefreshTokenService refreshTokens;
    private final ApplicationEventPublisher events;
    private final RoleAssignmentService roleAssignments;
    private final AccountEraser eraser;
    private final MemberDeparture organizations;
    private final TransactionOperations transactions;
    private final Clock clock;

    public AccountService(UserRepository users, CurrentPasswordCheck currentPassword,
                          RefreshTokenService refreshTokens, ApplicationEventPublisher events,
                          RoleAssignmentService roleAssignments, AccountEraser eraser,
                          MemberDeparture organizations, TransactionOperations transactions, Clock clock) {
        this.users = users;
        this.currentPassword = currentPassword;
        this.refreshTokens = refreshTokens;
        this.events = events;
        this.roleAssignments = roleAssignments;
        this.eraser = eraser;
        this.organizations = organizations;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * {@code readOnly = true} tells Hibernate it may skip dirty checking and lets the JDBC
     * driver and database optimise the read. It documents intent too: nothing here writes.
     */
    @Transactional(readOnly = true)
    public UserSummary currentUser(UUID userId) {
        // An access token issued before erasure lives up to 15 minutes; it must not show the placeholder.
        return users.findSummaryById(userId)
                .filter(summary -> summary.status() != UserStatus.ERASED)
                .orElseThrow(AccountService::accountUnavailable);
    }

    /**
     * Deactivates the account after re-confirming the password, and ends every session.
     * Reactivation is an administrator action (Phase 3). The last active administrator is refused
     * (409 {@code RBAC_LAST_ADMIN}), or nobody could administer the system afterwards; so is the only
     * owner of an open organisation (409 {@code ORG_LAST_OWNER}), or nobody could manage that seller.
     */
    public void deactivate(UUID userId, String password) {
        Instant now = Instant.now(clock);
        User user = users.findById(userId).filter(User::isActive).orElseThrow(AccountService::accountUnavailable);
        currentPassword.verify(user, password, now);

        transactions.executeWithoutResult(status -> {
            roleAssignments.requireCanLeave(userId); // locks the ADMIN row until commit
            organizations.requireNotSoleOwner(userId); // memberships stay: deactivation is reversible
            User managed = users.findById(userId).orElseThrow(AccountService::accountUnavailable);
            managed.deactivate(now);
            String email = managed.getEmail();
            refreshTokens.revokeAll(userId, RevokeReason.ACCOUNT_DEACTIVATED);
            events.publishEvent(new AccountDeactivated(email));
        });
        log.info("Account deactivated by owner: userId={}", userId);
    }

    /**
     * Erases the account on the owner's request after re-confirming the password: the email and
     * password are replaced, every session, email link and role is deleted. Irreversible; the
     * email address can be registered again afterwards. The last active administrator (409
     * {@code RBAC_LAST_ADMIN}) and the only owner of an open organisation (409 {@code ORG_LAST_OWNER})
     * are refused.
     */
    public void erase(UUID userId, String password) {
        Instant now = Instant.now(clock);
        User user = users.findById(userId).filter(User::isActive).orElseThrow(AccountService::accountUnavailable);
        currentPassword.verify(user, password, now); // hashing stays outside the transaction

        AccountEraser.Erasure erasure = transactions.execute(status -> {
            User managed = users.findById(userId).filter(User::isActive).orElseThrow(AccountService::accountUnavailable);
            return eraser.erase(managed, userId, now);
        });
        log.info("Account erased by owner: userId={}, {}", userId, erasure);
    }

    /** A valid token whose account was deleted or switched off meanwhile. */
    static BusinessException accountUnavailable() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "This account is no longer available.");
    }
}
