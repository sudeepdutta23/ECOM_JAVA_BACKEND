package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.dto.AdminUserDetail;
import com.ecommerce.backend.auth.dto.AdminUserSummary;
import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.repository.RefreshTokenRepository;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.auth.repository.UserSearch;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.rbac.service.AuthorizationService;
import com.ecommerce.backend.rbac.service.RoleAssignmentService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrators finding, inspecting and acting on accounts (Phase 3).
 *
 * <p>Lives in auth because auth owns the {@code User} entity and its rules; see docs/modules/USERS.md.
 *
 * <p>Every action checks, in this order:
 * <ol>
 *   <li>not your own account (403 {@code USER_SELF_ACTION_DENIED}): use your own account endpoints;</li>
 *   <li>the account exists (404);</li>
 *   <li>you hold every permission the account holds (403 {@code RBAC_ESCALATION_DENIED});</li>
 *   <li>the action fits the current status (409 {@code USER_INVALID_STATUS});</li>
 *   <li>for suspension: not the last active administrator (409 {@code RBAC_LAST_ADMIN}).</li>
 * </ol>
 *
 * <p>The suspension reason is an internal note: stored and shown to administrators, never logged.
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    /** Newest first. IDs are UUID v7, so this is creation order, and unique (a stable tie-breaker). */
    static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuthorizationService authorization;
    private final RefreshTokenService sessions;
    private final RoleAssignmentService roleAssignments;
    private final Clock clock;

    public UserAdminService(UserRepository users, RefreshTokenRepository refreshTokens,
                            AuthorizationService authorization, RefreshTokenService sessions,
                            RoleAssignmentService roleAssignments, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.authorization = authorization;
        this.sessions = sessions;
        this.roleAssignments = roleAssignments;
        this.clock = clock;
    }

    /**
     * @param email any case and surrounding spaces; normalized like stored emails, so the
     *              comparison stays case-insensitive without {@code lower()} defeating the index
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserSummary> search(UserStatus status, String email, int page, int size) {
        String prefix = email == null || email.isBlank() ? null : User.normalizeEmail(email);
        Instant now = Instant.now(clock);
        return PageResponse.from(
                users.findAll(new UserSearch(status, prefix).toSpecification(), PageRequest.of(page, size, NEWEST_FIRST)),
                user -> AdminUserSummary.from(user, now));
    }

    @Transactional(readOnly = true)
    public AdminUserDetail get(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User"));
        Instant now = Instant.now(clock);
        return AdminUserDetail.from(user, authorization.authoritiesOf(userId),
                refreshTokens.countByUser_IdAndRevokedAtIsNullAndExpiresAtAfter(userId, now), now);
    }

    // ------------------------------------------------------------------ actions

    /**
     * Blocks the account and ends every session. Already-issued access tokens expire within 15
     * minutes; refresh is refused at once, because refresh re-checks the status.
     */
    @Transactional
    public AdminUserDetail suspend(UUID callerId, UUID userId, String reason) {
        User user = requireManageable(callerId, userId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw invalidStatus(user, "only ACTIVE accounts can be suspended");
        }
        roleAssignments.requireCanLeave(userId); // locks the ADMIN row until commit
        user.suspend(reason, callerId, Instant.now(clock));
        int ended = sessions.revokeAll(userId, RevokeReason.ACCOUNT_SUSPENDED); // flushes the status first
        log.info("Account suspended: userId={}, by={}, sessionsEnded={}", userId, callerId, ended);
        return get(userId);
    }

    /** Reopens a suspended or self-deactivated account. The owner signs in again as usual. */
    @Transactional
    public AdminUserDetail reactivate(UUID callerId, UUID userId) {
        User user = requireManageable(callerId, userId);
        if (user.getStatus() != UserStatus.SUSPENDED && user.getStatus() != UserStatus.DEACTIVATED) {
            throw invalidStatus(user, "only SUSPENDED or DEACTIVATED accounts can be reactivated");
        }
        UserStatus previous = user.getStatus();
        user.reactivate(callerId, Instant.now(clock));
        log.info("Account reactivated: userId={}, previousStatus={}, by={}", userId, previous, callerId);
        return get(userId);
    }

    /** Ends a temporary lockout early, for example after support verified the owner. Idempotent. */
    @Transactional
    public AdminUserDetail unlock(UUID callerId, UUID userId) {
        requireManageable(callerId, userId);
        users.clearLockout(userId, Instant.now(clock));
        log.info("Account unlocked: userId={}, by={}", userId, callerId);
        return get(userId);
    }

    /** Ends every session of the account, for example after a reported device theft. Idempotent. */
    @Transactional
    public AdminUserDetail signOut(UUID callerId, UUID userId) {
        requireManageable(callerId, userId);
        int ended = sessions.revokeAll(userId, RevokeReason.ADMIN_SIGN_OUT);
        log.info("Account signed out by administrator: userId={}, by={}, sessionsEnded={}", userId, callerId, ended);
        return get(userId);
    }

    // ------------------------------------------------------------------ guards

    private User requireManageable(UUID callerId, UUID userId) {
        if (callerId.equals(userId)) {
            throw new BusinessException(ErrorCode.USER_SELF_ACTION_DENIED,
                    "Use your own account endpoints for your own account.");
        }
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User"));
        authorization.requireCanManage(callerId, userId);
        return user;
    }

    private static BusinessException invalidStatus(User user, String rule) {
        return new BusinessException(ErrorCode.USER_INVALID_STATUS,
                "This account is " + user.getStatus() + "; " + rule + ".");
    }
}
