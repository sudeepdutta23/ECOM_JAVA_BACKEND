package com.ecommerce.backend.rbac.service;

import com.ecommerce.backend.rbac.Permission;
import com.ecommerce.backend.rbac.entity.Role;
import com.ecommerce.backend.rbac.entity.UserRole;
import com.ecommerce.backend.rbac.repository.RoleRepository;
import com.ecommerce.backend.rbac.repository.UserRoleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rbac module's public API for other modules.
 *
 * <p><b>Dependency direction:</b> {@code auth -> rbac}. The auth module asks "what may this user
 * do?" and "give a new user the default role". The rbac module never imports auth classes; it
 * knows users only by ID. That keeps the graph acyclic, and rbac could later become a separate
 * service behind this same interface.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final UserRoleRepository userRoles;
    private final RoleRepository roles;
    private final Clock clock;

    public AuthorizationService(UserRoleRepository userRoles, RoleRepository roles, Clock clock) {
        this.userRoles = userRoles;
        this.roles = roles;
        this.clock = clock;
    }

    /** Roles and resolved permissions for the access token. One indexed query. */
    @Transactional(readOnly = true)
    public UserAuthorities authoritiesOf(UUID userId) {
        Set<String> roleCodes = new HashSet<>();
        Set<String> permissionCodes = new HashSet<>();
        for (UserRoleRepository.RolePermissionRow row : userRoles.findRolesAndPermissions(userId)) {
            roleCodes.add(row.getRoleCode());
            if (row.getPermissionCode() != null) {
                permissionCodes.add(row.getPermissionCode());
            }
        }
        return UserAuthorities.of(roleCodes, permissionCodes);
    }

    /**
     * The escalation rule applied to people: the caller must hold every permission the target
     * account holds. A support agent with {@code user:manage} can then suspend customers, but not
     * an administrator, so the permission cannot be used to lock out everyone above you.
     *
     * <p>Both sides are read from the database, not from tokens.
     *
     * @throws com.ecommerce.backend.common.exception.BusinessException {@code RBAC_ESCALATION_DENIED}
     */
    @Transactional(readOnly = true)
    public void requireCanManage(UUID callerId, UUID targetUserId) {
        Set<Permission> target = EnumSet.noneOf(Permission.class);
        authoritiesOf(targetUserId).permissions().forEach(code -> target.add(Permission.fromCode(code)));
        EscalationGuard.requireWithin(callerId, authoritiesOf(callerId), target, "manage an account");
    }

    /**
     * Gives a freshly registered user the {@code CUSTOMER} role, inside the registration
     * transaction, so an account never exists without a role.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assignDefaultRole(UUID userId) {
        Role customer = roles.findByCode(Role.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("System role CUSTOMER is missing; check migration V3"));
        userRoles.save(UserRole.grant(userId, customer.getId(), Instant.now(clock), null));
        log.debug("Default role granted: userId={}, role={}", userId, Role.CUSTOMER);
    }
}
