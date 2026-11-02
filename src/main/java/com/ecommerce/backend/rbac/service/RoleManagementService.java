package com.ecommerce.backend.rbac.service;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.DuplicateResourceException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.rbac.Permission;
import com.ecommerce.backend.rbac.entity.Role;
import com.ecommerce.backend.rbac.repository.RoleRepository;
import com.ecommerce.backend.rbac.repository.UserRoleRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create, edit and delete custom roles.
 *
 * <p><b>The escalation rule:</b> you may only manage roles that sit within your own power. Every
 * permission in the role, before AND after your change, must be one you hold. Otherwise anyone
 * with {@code role:write} could create an all-powerful role, or edit a stronger role to strip it.
 *
 * <p><b>Whose permissions?</b> The caller's CURRENT permissions from the database, not the ones in
 * their access token. A token can be up to 15 minutes old; a demoted admin must not be able to
 * use it to hand out powers they no longer have.
 *
 * <p>System roles ({@code ADMIN}, {@code CUSTOMER}) are read-only here. They are managed by
 * migrations, because the application depends on them; removing a permission from {@code ADMIN}
 * through the API could lock every administrator out.
 */
@Service
public class RoleManagementService {

    private static final Logger log = LoggerFactory.getLogger(RoleManagementService.class);

    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final AuthorizationService authorization;

    public RoleManagementService(RoleRepository roles, UserRoleRepository userRoles,
                                 AuthorizationService authorization) {
        this.roles = roles;
        this.userRoles = userRoles;
        this.authorization = authorization;
    }

    public record RoleCommand(String name, String description, Set<String> permissionCodes) {
    }

    @Transactional(readOnly = true)
    public List<Role> list() {
        return roles.findAllWithPermissions();
    }

    @Transactional(readOnly = true)
    public Role get(UUID roleId) {
        return roles.findWithPermissionsById(roleId).orElseThrow(() -> new ResourceNotFoundException("Role"));
    }

    @Transactional
    public Role create(UUID callerId, String code, RoleCommand command) {
        Set<Permission> permissions = parse(command.permissionCodes());
        requireWithinCallerPower(callerId, permissions);
        if (roles.existsByCode(code)) {
            throw new DuplicateResourceException("A role with code " + code + " already exists");
        }
        // saveAndFlush: assigns version and audit timestamps now, so the response shows them.
        // A concurrent duplicate code still fails safely on uk_roles_code (409 via the handler).
        Role role = roles.saveAndFlush(Role.custom(code, command.name(), command.description(), permissions));
        log.info("Role created: roleId={}, code={}, permissions={}, by={}", role.getId(), code, codes(permissions), callerId);
        return role;
    }

    @Transactional
    public Role update(UUID callerId, UUID roleId, RoleCommand command, long expectedVersion) {
        Role role = get(roleId);
        requireCustom(role);
        if (role.getVersion() != expectedVersion) {
            // Fast, explicit check. @Version still protects the window between this read and the commit.
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "This role was changed by someone else. Reload it and try again.");
        }
        Set<Permission> newPermissions = parse(command.permissionCodes());
        Set<Permission> touched = EnumSet.noneOf(Permission.class);
        touched.addAll(role.getPermissions());
        touched.addAll(newPermissions);
        requireWithinCallerPower(callerId, touched);

        role.update(command.name(), command.description(), newPermissions);
        roles.flush(); // bump the version now so the response carries the new value
        log.info("Role updated: roleId={}, code={}, permissions={}, by={}", roleId, role.getCode(),
                codes(newPermissions), callerId);
        return role;
    }

    @Transactional
    public void delete(UUID callerId, UUID roleId) {
        Role role = get(roleId);
        requireCustom(role);
        requireWithinCallerPower(callerId, role.getPermissions());
        long holders = userRoles.countByRoleId(roleId);
        if (holders > 0) {
            // fk_user_roles_role (RESTRICT) is the backstop if an assignment races in.
            throw new BusinessException(ErrorCode.RBAC_ROLE_IN_USE,
                    "This role is assigned to " + holders + " user(s). Revoke it from them first.");
        }
        roles.delete(role);
        log.info("Role deleted: roleId={}, code={}, by={}", roleId, role.getCode(), callerId);
    }

    // ------------------------------------------------------------------ guards

    private void requireWithinCallerPower(UUID callerId, Set<Permission> permissions) {
        EscalationGuard.requireWithin(callerId, authorization.authoritiesOf(callerId), permissions, "manage a role");
    }

    private static void requireCustom(Role role) {
        if (role.isSystemRole()) {
            throw new BusinessException(ErrorCode.RBAC_SYSTEM_ROLE,
                    "System role " + role.getCode() + " is managed by the application and cannot be changed.");
        }
    }

    /** Unknown codes are a client error, reported on the {@code permissions} field. */
    private static Set<Permission> parse(Set<String> codes) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        Set<String> unknown = new TreeSet<>();
        for (String code : codes) {
            try {
                permissions.add(Permission.fromCode(code));
            } catch (IllegalArgumentException e) {
                unknown.add(code);
            }
        }
        if (!unknown.isEmpty()) {
            throw RequestValidationException.of("permissions", "unknown permission(s): " + unknown);
        }
        return permissions;
    }

    private static Set<String> codes(Set<Permission> permissions) {
        return permissions.stream().map(Permission::code).collect(Collectors.toCollection(TreeSet::new));
    }
}
