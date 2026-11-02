package com.ecommerce.backend.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.rbac.Permission;
import com.ecommerce.backend.rbac.entity.Role;
import com.ecommerce.backend.rbac.repository.RoleRepository;
import com.ecommerce.backend.rbac.repository.UserRoleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class RoleAssignmentServiceTest {

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final Set<String> ALL_PERMISSIONS = Arrays.stream(Permission.values())
            .map(Permission::code).collect(Collectors.toSet());

    private final RoleRepository roles = mock(RoleRepository.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final RoleAssignmentService service = new RoleAssignmentService(roles, userRoles, authorization, users,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Role admin;
    private Role customer;

    @BeforeEach
    void setUp() {
        when(authorization.authoritiesOf(CALLER))
                .thenReturn(UserAuthorities.of(Set.of("ADMIN"), ALL_PERMISSIONS)); // like a real admin
        when(users.exists(TARGET)).thenReturn(true);
        admin = stored(Role.ADMIN, EnumSet.allOf(Permission.class), true);
        customer = stored(Role.CUSTOMER, EnumSet.noneOf(Permission.class), true);
        when(roles.lockByCode(Role.ADMIN)).thenReturn(Optional.of(admin));
    }

    private Role stored(String code, Set<Permission> permissions, boolean system) {
        Role role = Role.custom(code, code, null, permissions);
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(role, "systemRole", system);
        when(roles.findWithPermissionsById(role.getId())).thenReturn(Optional.of(role));
        return role;
    }

    // ------------------------------------------------------------------ grant

    @Test
    void grantRecordsWhoGrantedItAndWhen() {
        Role support = stored("SUPPORT", EnumSet.of(Permission.ROLE_READ), false);
        when(userRoles.grantIfAbsent(TARGET, support.getId(), NOW, CALLER)).thenReturn(1);

        service.grant(CALLER, TARGET, support.getId());

        verify(userRoles).grantIfAbsent(TARGET, support.getId(), NOW, CALLER);
    }

    @Test
    void grantingARoleAlreadyHeldStillSucceeds() {
        when(userRoles.grantIfAbsent(any(), any(), any(), any())).thenReturn(0);

        service.grant(CALLER, TARGET, admin.getId()); // no exception: idempotent
    }

    @Test
    void cannotGrantToYourself() {
        assertCode(() -> service.grant(CALLER, CALLER, admin.getId()), ErrorCode.RBAC_SELF_MODIFICATION_DENIED);
        verify(userRoles, never()).grantIfAbsent(any(), any(), any(), any());
    }

    @Test
    void cannotGrantARoleStrongerThanYou() {
        when(authorization.authoritiesOf(CALLER))
                .thenReturn(UserAuthorities.of(Set.of("EDITOR"), Set.of("role:read", "role:assign")));
        Role writer = stored("WRITER", EnumSet.of(Permission.ROLE_WRITE), false);

        assertCode(() -> service.grant(CALLER, TARGET, writer.getId()), ErrorCode.RBAC_ESCALATION_DENIED);
        verify(userRoles, never()).grantIfAbsent(any(), any(), any(), any());
    }

    @Test
    void unknownUserOrRoleIs404() {
        UUID ghost = UUID.randomUUID();
        when(roles.findWithPermissionsById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grant(CALLER, ghost, admin.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.grant(CALLER, TARGET, ghost)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ revoke

    @Test
    void revokesACustomRoleWithoutTakingTheAdminLock() {
        Role support = stored("SUPPORT", EnumSet.of(Permission.ROLE_READ), false);

        service.revoke(CALLER, TARGET, support.getId());

        verify(userRoles).revoke(TARGET, support.getId());
        verify(roles, never()).lockByCode(any());
    }

    @Test
    void customerCannotBeRevoked() {
        assertCode(() -> service.revoke(CALLER, TARGET, customer.getId()), ErrorCode.RBAC_SYSTEM_ROLE);
        verify(userRoles, never()).revoke(any(), any());
    }

    @Test
    void cannotRevokeFromYourself() {
        assertCode(() -> service.revoke(CALLER, CALLER, admin.getId()), ErrorCode.RBAC_SELF_MODIFICATION_DENIED);
    }

    @Test
    void cannotDemoteSomeoneStrongerThanYou() {
        when(authorization.authoritiesOf(CALLER))
                .thenReturn(UserAuthorities.of(Set.of("EDITOR"), Set.of("role:read", "role:assign")));

        assertCode(() -> service.revoke(CALLER, TARGET, admin.getId()), ErrorCode.RBAC_ESCALATION_DENIED);
        verify(userRoles, never()).revoke(any(), any());
    }

    @Test
    void revokingAdminLocksTheAdminRowBeforeCheckingAnything() {
        when(userRoles.findUserIdsByRoleId(admin.getId())).thenReturn(List.of(TARGET, CALLER));
        when(users.countActive(anyCollection())).thenReturn(1L);

        service.revoke(CALLER, TARGET, admin.getId());

        InOrder order = inOrder(roles, authorization, userRoles);
        order.verify(roles).lockByCode(Role.ADMIN);
        order.verify(authorization).authoritiesOf(CALLER);
        order.verify(userRoles).revoke(TARGET, admin.getId());
    }

    @Test
    void theLastActiveAdminCannotBeRevoked() {
        UUID deactivatedAdmin = UUID.randomUUID();
        when(userRoles.findUserIdsByRoleId(admin.getId())).thenReturn(List.of(TARGET, deactivatedAdmin));
        when(users.countActive(List.of(deactivatedAdmin))).thenReturn(0L); // the others cannot sign in
        when(users.countActive(List.of(TARGET, deactivatedAdmin))).thenReturn(1L);

        assertCode(() -> service.revoke(CALLER, TARGET, admin.getId()), ErrorCode.RBAC_LAST_ADMIN);
        verify(userRoles, never()).revoke(any(), any());
    }

    @Test
    void anInactiveAdminCanLoseTheRoleEvenIfNoActiveAdminRemains() {
        // Revoking from a deactivated account does not change who can administer the system.
        when(userRoles.findUserIdsByRoleId(admin.getId())).thenReturn(List.of(TARGET));
        when(users.countActive(anyCollection())).thenReturn(0L);

        service.revoke(CALLER, TARGET, admin.getId());

        verify(userRoles).revoke(TARGET, admin.getId());
    }

    // ------------------------------------------------------------------ leaving (deactivation)

    @Test
    void aNonAdminMayAlwaysLeave() {
        when(userRoles.findUserIdsByRoleId(admin.getId())).thenReturn(List.of(CALLER));

        service.requireCanLeave(TARGET); // no exception

        verify(roles).lockByCode(Role.ADMIN);
    }

    @Test
    void theLastActiveAdminMayNotLeave() {
        when(userRoles.findUserIdsByRoleId(admin.getId())).thenReturn(List.of(TARGET));
        when(users.countActive(List.of())).thenReturn(0L);
        when(users.countActive(List.of(TARGET))).thenReturn(1L);

        assertCode(() -> service.requireCanLeave(TARGET), ErrorCode.RBAC_LAST_ADMIN);
    }

    // ------------------------------------------------------------------ bootstrap

    @Test
    void bootstrapGrantsAdminWhenNoActiveAdminExists() {
        when(userRoles.findUserIdsByRoleId(admin.getId())).thenReturn(List.of());
        when(users.findActiveUserId("first@example.com")).thenReturn(Optional.of(TARGET));

        assertThat(service.bootstrapAdmin("first@example.com")).isEqualTo(RoleAssignmentService.BootstrapOutcome.GRANTED);
        verify(userRoles).grantIfAbsent(eq(TARGET), eq(admin.getId()), eq(NOW), eq(null));
    }

    @Test
    void bootstrapDoesNothingOnceAnAdminExists() {
        when(userRoles.findUserIdsByRoleId(admin.getId())).thenReturn(List.of(CALLER));
        when(users.countActive(List.of(CALLER))).thenReturn(1L);

        assertThat(service.bootstrapAdmin("intruder@example.com"))
                .isEqualTo(RoleAssignmentService.BootstrapOutcome.ADMIN_ALREADY_EXISTS);
        verify(users, never()).findActiveUserId(any());
        verify(userRoles, never()).grantIfAbsent(any(), any(), any(), any());
    }

    @Test
    void bootstrapWaitsForAnActiveAccount() {
        when(userRoles.findUserIdsByRoleId(admin.getId())).thenReturn(List.of());
        when(users.findActiveUserId(any())).thenReturn(Optional.empty());

        assertThat(service.bootstrapAdmin("pending@example.com"))
                .isEqualTo(RoleAssignmentService.BootstrapOutcome.NO_ACTIVE_ACCOUNT);
        verify(userRoles, never()).grantIfAbsent(any(), any(), any(), any());
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
