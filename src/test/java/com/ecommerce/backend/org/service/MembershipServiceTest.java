package com.ecommerce.backend.org.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.entity.OrganizationMember;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.repository.OrganizationRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class MembershipServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final MembershipService service = new MembershipService(organizations, members,
            new OrganizationGuard(members), users);

    private Organization organization;

    @BeforeEach
    void setUp() {
        organization = Organization.create("acme-store", "Acme", UUID.randomUUID(), NOW);
        ReflectionTestUtils.setField(organization, "id", UUID.randomUUID());
        when(organizations.findByIdForUpdate(organization.getId())).thenReturn(Optional.of(organization));
        when(users.emailsOf(any())).thenReturn(Map.of());
    }

    private UUID member(OrgRole role) {
        UUID userId = UUID.randomUUID();
        when(members.findById(new OrganizationMember.Key(organization.getId(), userId)))
                .thenReturn(Optional.of(OrganizationMember.join(organization.getId(), userId, role, NOW, null)));
        when(members.findMembership(organization.getId(), userId)).thenAnswer(i -> Optional.of(
                new OrgMembership(organization.getId(), userId, role, organization.getStatus())));
        return userId;
    }

    /** The organisation's owners, and how many of the others the account directory reports as active. */
    private void owners(List<UUID> ownerIds, long activeOthers) {
        when(members.findOwnerIds(organization.getId())).thenReturn(ownerIds);
        when(users.countActive(any())).thenReturn(activeOthers);
    }

    // ------------------------------------------------------------------ change role

    @Test
    void anOwnerPromotesStaffToManagerUnderTheOrganizationLock() {
        UUID owner = member(OrgRole.OWNER);
        UUID staff = member(OrgRole.STAFF);

        assertThat(service.changeRole(owner, organization.getId(), staff, OrgRole.MANAGER).role()).isEqualTo(OrgRole.MANAGER);

        InOrder order = inOrder(organizations, members);
        order.verify(organizations).findByIdForUpdate(organization.getId());
        order.verify(members).findMembership(organization.getId(), owner);
    }

    @Test
    void managersCannotCreateManagersOrTouchOwners() {
        UUID manager = member(OrgRole.MANAGER);
        UUID staff = member(OrgRole.STAFF);
        UUID owner = member(OrgRole.OWNER);

        assertCode(() -> service.changeRole(manager, organization.getId(), staff, OrgRole.MANAGER), ErrorCode.ORG_RANK_DENIED);
        assertCode(() -> service.changeRole(manager, organization.getId(), owner, OrgRole.STAFF), ErrorCode.ORG_RANK_DENIED);
        assertCode(() -> service.remove(manager, organization.getId(), owner), ErrorCode.ORG_RANK_DENIED);
        verify(members, never()).delete(any());
    }

    @Test
    void nobodyChangesOrRemovesTheirOwnMembership() {
        UUID owner = member(OrgRole.OWNER);

        assertCode(() -> service.changeRole(owner, organization.getId(), owner, OrgRole.STAFF), ErrorCode.ORG_SELF_ACTION_DENIED);
        assertCode(() -> service.remove(owner, organization.getId(), owner), ErrorCode.ORG_SELF_ACTION_DENIED);
        verifyNoInteractions(organizations);
    }

    @Test
    void anOwnerWhoLeavesNoOtherActiveOwnerIsRefusedEvenIfInactiveOwnersRemain() {
        UUID owner = member(OrgRole.OWNER);
        UUID deactivatedOwner = member(OrgRole.OWNER);
        owners(List.of(owner, deactivatedOwner), 0); // the other owner's account cannot sign in

        assertCode(() -> service.leave(owner, organization.getId()), ErrorCode.ORG_LAST_OWNER);
        verify(users).countActive(List.of(deactivatedOwner));
        verify(members, never()).deleteById(any());
    }

    @Test
    void oneOfTwoActiveOwnersMayBeRemoved() {
        UUID owner = member(OrgRole.OWNER);
        UUID coOwner = member(OrgRole.OWNER);
        owners(List.of(owner, coOwner), 1);

        service.remove(owner, organization.getId(), coOwner);

        verify(members).delete(any());
    }

    @Test
    void outsidersAndStaffAreTurnedAway() {
        UUID staff = member(OrgRole.STAFF);
        UUID other = member(OrgRole.STAFF);

        assertThatThrownBy(() -> service.remove(UUID.randomUUID(), organization.getId(), other))
                .isInstanceOf(ResourceNotFoundException.class);
        assertCode(() -> service.remove(staff, organization.getId(), other), ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.remove(UUID.randomUUID(), UUID.randomUUID(), other))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSuspendedOrganizationsMembersCannotBeChanged() {
        UUID owner = member(OrgRole.OWNER);
        UUID staff = member(OrgRole.STAFF);
        organization.approve(UUID.randomUUID(), NOW);
        organization.suspend("Fraud", UUID.randomUUID(), NOW);

        assertCode(() -> service.remove(owner, organization.getId(), staff), ErrorCode.ORG_INVALID_STATUS);
    }

    // ------------------------------------------------------------------ leave

    @Test
    void theLastOwnerCannotLeaveAnOpenOrganizationButMayLeaveAClosedOne() {
        UUID owner = member(OrgRole.OWNER);
        owners(List.of(owner), 0);
        assertCode(() -> service.leave(owner, organization.getId()), ErrorCode.ORG_LAST_OWNER);

        organization.close(owner, NOW);
        service.leave(owner, organization.getId());
        verify(members).deleteById(new OrganizationMember.Key(organization.getId(), owner));
    }

    @Test
    void staffMayLeaveEvenWhileSuspended() {
        UUID staff = member(OrgRole.STAFF);
        organization.approve(UUID.randomUUID(), NOW);
        organization.suspend("Fraud", UUID.randomUUID(), NOW);

        service.leave(staff, organization.getId());

        verify(members).deleteById(new OrganizationMember.Key(organization.getId(), staff));
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
