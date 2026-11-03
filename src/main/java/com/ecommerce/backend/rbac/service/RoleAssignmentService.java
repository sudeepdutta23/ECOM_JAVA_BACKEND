package com.ecommerce.backend.rbac.service;

import com.ecommerce.backend.common.audit.Audited;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.util.Masking;
import com.ecommerce.backend.rbac.entity.Role;
import com.ecommerce.backend.rbac.repository.RoleRepository;
import com.ecommerce.backend.rbac.repository.UserRoleRepository;
import com.ecommerce.backend.rbac.repository.UserRoleView;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Granting and revoking roles.
 *
 * <p>Rules, checked in this order:
 * <ol>
 *   <li>Nobody changes their own roles. A second person is always involved, and a compromised
 *       admin account cannot quietly promote itself further or hide by demoting itself.</li>
 *   <li>The user and the role must exist (404).</li>
 *   <li>You may only grant or revoke a role whose permissions you all hold yourself.</li>
 *   <li>{@code CUSTOMER} cannot be revoked: every account keeps its baseline role.</li>
 *   <li>The number of active {@code ADMIN} holders never drops from one to zero.</li>
 * </ol>
 *
 * <p>Grant and revoke are idempotent: repeating one changes nothing and still succeeds, which is
 * what HTTP PUT and DELETE promise and what makes client retries safe.
 */
@Service
public class RoleAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(RoleAssignmentService.class);

    public enum BootstrapOutcome { GRANTED, ADMIN_ALREADY_EXISTS, NO_ACTIVE_ACCOUNT }

    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final AuthorizationService authorization;
    private final UserDirectory users;
    private final Clock clock;

    public RoleAssignmentService(RoleRepository roles, UserRoleRepository userRoles,
                                 AuthorizationService authorization, UserDirectory users, Clock clock) {
        this.roles = roles;
        this.userRoles = userRoles;
        this.authorization = authorization;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserRoleView> rolesOf(UUID userId) {
        requireUser(userId);
        return userRoles.findAssignments(userId);
    }

    @Transactional
    @Audited(action = "role.grant", targetType = "user", targetId = "#userId", detail = "'role=' + #roleId")
    public void grant(UUID callerId, UUID userId, UUID roleId) {
        requireNotSelf(callerId, userId);
        requireUser(userId);
        Role role = requireRole(roleId);
        EscalationGuard.requireWithin(callerId, authorization.authoritiesOf(callerId), role.getPermissions(), "grant a role");

        // No lock needed: granting can only increase the number of admins.
        if (userRoles.grantIfAbsent(userId, roleId, Instant.now(clock), callerId) == 1) {
            log.info("Role granted: userId={}, role={}, by={}", userId, role.getCode(), callerId);
        }
    }

    @Transactional
    @Audited(action = "role.revoke", targetType = "user", targetId = "#userId", detail = "'role=' + #roleId")
    public void revoke(UUID callerId, UUID userId, UUID roleId) {
        requireNotSelf(callerId, userId);
        requireUser(userId);
        Role role = requireRole(roleId);
        if (Role.CUSTOMER.equals(role.getCode())) {
            throw new BusinessException(ErrorCode.RBAC_SYSTEM_ROLE,
                    "CUSTOMER is every account's baseline role and cannot be revoked.");
        }
        boolean adminRole = Role.ADMIN.equals(role.getCode());
        if (adminRole) {
            // Lock BEFORE any check, so everything below sees the state left by a concurrent
            // revocation that committed while we waited (READ COMMITTED re-reads per statement).
            roles.lockByCode(Role.ADMIN);
        }
        EscalationGuard.requireWithin(callerId, authorization.authoritiesOf(callerId), role.getPermissions(), "revoke a role");
        if (adminRole) {
            requireNotLastActiveAdmin(role.getId(), userId,
                    "This is the last active administrator. Grant ADMIN to someone else first.");
        }

        if (userRoles.revoke(userId, roleId) == 1) {
            log.info("Role revoked: userId={}, role={}, by={}", userId, role.getCode(), callerId);
        }
    }

    /**
     * Makes the account with this email an administrator, but only while no active administrator
     * exists. Solves the chicken-and-egg problem of the first admin without a back door: once an
     * admin exists this does nothing, so leaving the setting in place is harmless.
     */
    @Transactional
    public BootstrapOutcome bootstrapAdmin(String email) {
        Role admin = roles.lockByCode(Role.ADMIN)   // several instances starting at once: one wins
                .orElseThrow(() -> new IllegalStateException("System role ADMIN is missing; check migration V3"));
        if (users.countActive(userRoles.findUserIdsByRoleId(admin.getId())) > 0) {
            log.info("Admin bootstrap skipped: an active administrator already exists");
            return BootstrapOutcome.ADMIN_ALREADY_EXISTS;
        }
        Optional<UUID> userId = users.findActiveUserId(email);
        if (userId.isEmpty()) {
            log.warn("Admin bootstrap: no active account for {}; register and verify it, then restart",
                    Masking.email(email));
            return BootstrapOutcome.NO_ACTIVE_ACCOUNT;
        }
        userRoles.grantIfAbsent(userId.get(), admin.getId(), Instant.now(clock), null);
        log.warn("Admin bootstrap: ADMIN granted to userId={}", userId.get()); // WARN: security-relevant
        return BootstrapOutcome.GRANTED;
    }

    // ------------------------------------------------------------------ guards

    private static void requireNotSelf(UUID callerId, UUID userId) {
        if (callerId.equals(userId)) {
            throw new BusinessException(ErrorCode.RBAC_SELF_MODIFICATION_DENIED,
                    "You cannot change your own roles. Ask another administrator.");
        }
    }

    private void requireUser(UUID userId) {
        if (!users.exists(userId)) {
            throw new ResourceNotFoundException("User");
        }
    }

    private Role requireRole(UUID roleId) {
        return roles.findWithPermissionsById(roleId).orElseThrow(() -> new ResourceNotFoundException("Role"));
    }

    /**
     * Called by the auth module before an account stops being active: deactivation, suspension,
     * erasure. Joins the caller's transaction, so the {@code ADMIN} row lock is held until the
     * status change commits: two last admins deactivating at once cannot both succeed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCanLeave(UUID userId) {
        Role admin = roles.lockByCode(Role.ADMIN)
                .orElseThrow(() -> new IllegalStateException("System role ADMIN is missing; check migration V3"));
        requireNotLastActiveAdmin(admin.getId(), userId,
                "This is the last active administrator. Grant ADMIN to someone else first.");
    }

    /**
     * Erasure: removes every role, {@code CUSTOMER} included, without the per-role rules of
     * {@link #revoke}. The auth module has already applied {@link #requireCanLeave} and the
     * escalation rule to the account as a whole, in this same transaction.
     *
     * @return the number of roles removed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int removeAllRoles(UUID userId) {
        return userRoles.revokeAll(userId);
    }

    /** Refuses to take the count of active admins from at least one down to zero. */
    private void requireNotLastActiveAdmin(UUID adminRoleId, UUID userId, String message) {
        List<UUID> holders = userRoles.findUserIdsByRoleId(adminRoleId);
        if (!holders.contains(userId)) {
            return;
        }
        List<UUID> remaining = holders.stream().filter(id -> !id.equals(userId)).toList();
        if (users.countActive(remaining) == 0 && users.countActive(holders) > 0) {
            throw new BusinessException(ErrorCode.RBAC_LAST_ADMIN, message);
        }
    }
}
