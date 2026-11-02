package com.ecommerce.backend.org.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.security.OpaqueTokens;
import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.entity.OrganizationInvitation;
import com.ecommerce.backend.org.entity.OrganizationMember;
import com.ecommerce.backend.org.event.InvitationCreated;
import com.ecommerce.backend.org.repository.OrganizationInvitationRepository;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.repository.OrganizationRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class InvitationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String RAW = "q2vQ8m1f0xY3bS9kLw7pT4rN6uC5aE2dH8jK1zR0sVg";

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final OrganizationInvitationRepository invitations = mock(OrganizationInvitationRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final InvitationService service = new InvitationService(organizations, members, invitations,
            new OrganizationGuard(members), users, new OpaqueTokens(), events, OrgFixtures.PROPERTIES,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Organization organization;
    private UUID owner;

    @BeforeEach
    void setUp() {
        organization = Organization.create("acme-store", "Acme", UUID.randomUUID(), NOW);
        ReflectionTestUtils.setField(organization, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(organization, "version", 0L);
        when(organizations.findById(organization.getId())).thenReturn(Optional.of(organization));
        owner = member(OrgRole.OWNER);
        when(invitations.save(any(OrganizationInvitation.class))).thenAnswer(i -> i.getArgument(0));
        when(invitations.findOpen(any(), any())).thenReturn(Optional.empty());
        when(users.findActiveUserId(any())).thenReturn(Optional.empty());
    }

    private UUID member(OrgRole role) {
        UUID userId = UUID.randomUUID();
        when(members.findById(new OrganizationMember.Key(organization.getId(), userId)))
                .thenReturn(Optional.of(OrganizationMember.join(organization.getId(), userId, role, NOW, null)));
        when(members.findMembership(organization.getId(), userId)).thenAnswer(i -> Optional.of(
                new OrgMembership(organization.getId(), userId, role, organization.getStatus())));
        return userId;
    }

    private OrganizationInvitation stored(String email, OrgRole role) {
        OrganizationInvitation invitation = OrganizationInvitation.issue(organization.getId(), email, role,
                OpaqueTokens.hash(RAW), owner, NOW.minusSeconds(60), Duration.ofDays(7));
        when(invitations.findByTokenHashForUpdate(OpaqueTokens.hash(RAW))).thenReturn(Optional.of(invitation));
        return invitation;
    }

    // ------------------------------------------------------------------ invite

    @Test
    void anInvitationStoresOnlyTheHashAndEmailsTheRawLinkAfterCommit() {
        service.invite(owner, organization.getId(), " Bob@Example.com ", OrgRole.STAFF);

        ArgumentCaptor<OrganizationInvitation> saved = ArgumentCaptor.forClass(OrganizationInvitation.class);
        verify(invitations).save(saved.capture());
        ArgumentCaptor<InvitationCreated> event = ArgumentCaptor.forClass(InvitationCreated.class);
        verify(events).publishEvent(event.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("bob@example.com");
        assertThat(saved.getValue().getTokenHash()).isEqualTo(OpaqueTokens.hash(event.getValue().rawToken()));
        assertThat(event.getValue().email()).isEqualTo("bob@example.com");
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void managersInviteStaffOnly() {
        UUID manager = member(OrgRole.MANAGER);

        assertCode(() -> service.invite(manager, organization.getId(), "bob@example.com", OrgRole.MANAGER),
                ErrorCode.ORG_RANK_DENIED);
        assertCode(() -> service.invite(manager, organization.getId(), "bob@example.com", OrgRole.OWNER),
                ErrorCode.ORG_RANK_DENIED);
        service.invite(manager, organization.getId(), "bob@example.com", OrgRole.STAFF);
        verify(events).publishEvent(any(InvitationCreated.class));
    }

    @Test
    void existingMembersCannotBeInvitedAgain() {
        UUID bob = member(OrgRole.STAFF);
        when(users.findActiveUserId("bob@example.com")).thenReturn(Optional.of(bob));
        when(members.existsById(new OrganizationMember.Key(organization.getId(), bob))).thenReturn(true);

        assertCode(() -> service.invite(owner, organization.getId(), "bob@example.com", OrgRole.MANAGER),
                ErrorCode.ORG_ALREADY_MEMBER);
        verify(invitations, never()).save(any());
    }

    @Test
    void invitingTheSameAddressAgainReplacesTheOpenInvitationAndIsNotCountedTwice() {
        OrganizationInvitation previous = OrganizationInvitation.issue(organization.getId(), "bob@example.com",
                OrgRole.STAFF, "b".repeat(64), owner, NOW.minusSeconds(3600), Duration.ofDays(7));
        when(invitations.findOpen(organization.getId(), "bob@example.com")).thenReturn(Optional.of(previous));

        service.invite(owner, organization.getId(), "bob@example.com", OrgRole.MANAGER);

        assertThat(previous.getRevokedAt()).isEqualTo(NOW);
        InOrder order = inOrder(invitations);
        order.verify(invitations).flush(); // frees the partial unique index first
        order.verify(invitations).save(any());
        verify(invitations, never()).countPending(any(), any());
    }

    @Test
    void theNumberOfPendingInvitationsIsCapped() {
        when(invitations.countPending(organization.getId(), NOW)).thenReturn(50L);

        assertCode(() -> service.invite(owner, organization.getId(), "bob@example.com", OrgRole.STAFF),
                ErrorCode.ORG_INVITATION_LIMIT);
    }

    // ------------------------------------------------------------------ accept

    @Test
    void theInvitedAccountAcceptsOnceAndJoinsWithTheOfferedRole() {
        UUID bob = UUID.randomUUID();
        OrganizationInvitation invitation = stored("bob@example.com", OrgRole.MANAGER);
        when(users.isActive(bob)).thenReturn(true);
        when(users.emailsOf(List.of(bob))).thenReturn(Map.of(bob, "bob@example.com"));

        assertThat(service.accept(bob, RAW).myRole()).isEqualTo(OrgRole.MANAGER);

        assertThat(invitation.getAcceptedAt()).isEqualTo(NOW);
        ArgumentCaptor<OrganizationMember> joined = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(members).save(joined.capture());
        assertThat(joined.getValue().getAddedBy()).isEqualTo(owner);
    }

    @Test
    void aForwardedLinkDoesNotWorkForAnotherAccount() {
        UUID mallory = UUID.randomUUID();
        OrganizationInvitation invitation = stored("bob@example.com", OrgRole.OWNER);
        when(users.isActive(mallory)).thenReturn(true);
        when(users.emailsOf(List.of(mallory))).thenReturn(Map.of(mallory, "mallory@example.com"));

        assertCode(() -> service.accept(mallory, RAW), ErrorCode.ORG_INVALID_INVITATION);
        assertThat(invitation.getAcceptedAt()).isNull();
        verify(members, never()).save(any());
    }

    @Test
    void unknownExpiredAndRevokedLinksLookTheSame() {
        when(invitations.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());
        assertCode(() -> service.accept(UUID.randomUUID(), RAW), ErrorCode.ORG_INVALID_INVITATION);

        stored("bob@example.com", OrgRole.STAFF).revoke(NOW);
        assertCode(() -> service.accept(UUID.randomUUID(), RAW), ErrorCode.ORG_INVALID_INVITATION);
    }

    @Test
    void aSuspendedOrganizationCannotBeJoined() {
        UUID bob = UUID.randomUUID();
        stored("bob@example.com", OrgRole.STAFF);
        when(users.isActive(bob)).thenReturn(true);
        when(users.emailsOf(List.of(bob))).thenReturn(Map.of(bob, "bob@example.com"));
        organization.approve(owner, NOW);
        organization.suspend("Fraud", owner, NOW);

        assertCode(() -> service.accept(bob, RAW), ErrorCode.ORG_INVALID_STATUS);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
