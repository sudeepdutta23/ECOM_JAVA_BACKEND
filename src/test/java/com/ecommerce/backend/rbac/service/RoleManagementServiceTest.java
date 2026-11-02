package com.ecommerce.backend.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RoleManagementServiceTest {

    private static final UUID CALLER = UUID.randomUUID();

    private final RoleRepository roles = mock(RoleRepository.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RoleManagementService service = new RoleManagementService(roles, userRoles, authorization);

    @BeforeEach
    void callerHoldsReadAndWriteButNotAssign() {
        when(authorization.authoritiesOf(CALLER))
                .thenReturn(UserAuthorities.of(Set.of("ROLE_EDITOR"), Set.of("role:read", "role:write")));
        when(roles.saveAndFlush(any(Role.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Role stored(Set<Permission> permissions, boolean system) {
        Role role = Role.custom("SUPPORT_AGENT", "Support agent", null, permissions);
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(role, "id", id);
        ReflectionTestUtils.setField(role, "version", 3L);
        ReflectionTestUtils.setField(role, "systemRole", system);
        when(roles.findWithPermissionsById(id)).thenReturn(Optional.of(role));
        return role;
    }

    private static RoleManagementService.RoleCommand command(String... permissions) {
        return new RoleManagementService.RoleCommand("Support agent", "Helps customers", Set.of(permissions));
    }

    // ------------------------------------------------------------------ create

    @Test
    void createsARoleWithinTheCallersPower() {
        Role role = service.create(CALLER, "SUPPORT_AGENT", command("role:read"));

        assertThat(role.getCode()).isEqualTo("SUPPORT_AGENT");
        assertThat(role.getPermissions()).containsExactly(Permission.ROLE_READ);
        assertThat(role.isSystemRole()).isFalse();
    }

    @Test
    void cannotCreateARoleWithAPermissionTheCallerLacks() {
        assertCode(() -> service.create(CALLER, "SNEAKY", command("role:read", "role:assign")),
                ErrorCode.RBAC_ESCALATION_DENIED);
        verify(roles, never()).saveAndFlush(any());
    }

    @Test
    void unknownPermissionIsAValidationErrorOnThePermissionsField() {
        assertThatThrownBy(() -> service.create(CALLER, "X_ROLE", command("order:refund")))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> {
                    assertThat(e.getErrors().get(0).field()).isEqualTo("permissions");
                    assertThat(e.getErrors().get(0).message()).contains("order:refund");
                });
    }

    @Test
    void duplicateCodeIsRejected() {
        when(roles.existsByCode("ADMIN")).thenReturn(true);

        assertThatThrownBy(() -> service.create(CALLER, "ADMIN", command()))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // ------------------------------------------------------------------ update

    @Test
    void updateReplacesNameAndPermissions() {
        Role role = stored(EnumSet.of(Permission.ROLE_READ), false);

        service.update(CALLER, role.getId(), command("role:read", "role:write"), 3L);

        assertThat(role.getPermissions()).containsExactlyInAnyOrder(Permission.ROLE_READ, Permission.ROLE_WRITE);
        assertThat(role.getDescription()).isEqualTo("Helps customers");
    }

    @Test
    void staleVersionIsRejectedBeforeAnyChange() {
        Role role = stored(EnumSet.of(Permission.ROLE_READ), false);

        assertCode(() -> service.update(CALLER, role.getId(), command("role:write"), 2L), ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(role.getPermissions()).containsExactly(Permission.ROLE_READ);
    }

    @Test
    void cannotEditARoleStrongerThanTheCaller() {
        // Even removing permissions is refused: the caller would be managing powers they lack.
        Role stronger = stored(EnumSet.of(Permission.ROLE_READ, Permission.ROLE_ASSIGN), false);

        assertCode(() -> service.update(CALLER, stronger.getId(), command("role:read"), 3L),
                ErrorCode.RBAC_ESCALATION_DENIED);
        assertThat(stronger.getPermissions()).contains(Permission.ROLE_ASSIGN);
    }

    @Test
    void systemRolesAreReadOnly() {
        Role admin = stored(EnumSet.allOf(Permission.class), true);

        assertCode(() -> service.update(CALLER, admin.getId(), command(), 3L), ErrorCode.RBAC_SYSTEM_ROLE);
    }

    @Test
    void unknownRoleIs404() {
        assertThatThrownBy(() -> service.update(CALLER, UUID.randomUUID(), command(), 0L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deletesAnUnassignedCustomRole() {
        Role role = stored(EnumSet.of(Permission.ROLE_READ), false);
        when(userRoles.countByRoleId(role.getId())).thenReturn(0L);

        service.delete(CALLER, role.getId());

        verify(roles).delete(role);
    }

    @Test
    void cannotDeleteARoleThatIsStillAssigned() {
        Role role = stored(EnumSet.of(Permission.ROLE_READ), false);
        when(userRoles.countByRoleId(role.getId())).thenReturn(4L);

        assertThatThrownBy(() -> service.delete(CALLER, role.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RBAC_ROLE_IN_USE);
                    assertThat(e.getMessage()).contains("4 user(s)");
                });
        verify(roles, never()).delete(any());
    }

    @Test
    void cannotDeleteASystemRole() {
        Role customer = stored(EnumSet.noneOf(Permission.class), true);

        assertCode(() -> service.delete(CALLER, customer.getId()), ErrorCode.RBAC_SYSTEM_ROLE);
    }

    @Test
    void cannotDeleteARoleStrongerThanTheCaller() {
        Role stronger = stored(EnumSet.of(Permission.ROLE_ASSIGN), false);

        assertCode(() -> service.delete(CALLER, stronger.getId()), ErrorCode.RBAC_ESCALATION_DENIED);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
