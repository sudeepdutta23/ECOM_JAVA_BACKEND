package com.ecommerce.backend.org.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.org.dto.OrganizationResponse;
import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.entity.OrganizationMember;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.repository.OrganizationInvitationRepository;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.repository.OrganizationRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class OrganizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final UUID FOUNDER = UUID.randomUUID();

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final OrganizationInvitationRepository invitations = mock(OrganizationInvitationRepository.class);
    private final OrganizationService service = new OrganizationService(organizations, members, invitations,
            new OrganizationGuard(members), users, OrgFixtures.PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(users.isActive(FOUNDER)).thenReturn(true);
        when(organizations.saveAndFlush(any(Organization.class))).thenAnswer(i -> withId(i.getArgument(0)));
    }

    private static Organization withId(Organization organization) {
        ReflectionTestUtils.setField(organization, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(organization, "version", 0L);
        return organization;
    }

    /** An existing organisation with a member in the given role. */
    private Organization existing(UUID memberId, OrgRole role) {
        Organization organization = withId(Organization.create("acme-store", "Acme", FOUNDER, NOW));
        when(organizations.findById(organization.getId())).thenReturn(Optional.of(organization));
        stubMembership(organization, memberId, role);
        return organization;
    }

    /** The guard's one query; the status is read when called, so tests may change it afterwards. */
    private void stubMembership(Organization organization, UUID memberId, OrgRole role) {
        when(members.findMembership(organization.getId(), memberId)).thenAnswer(i -> Optional.of(
                new OrgMembership(organization.getId(), memberId, role, organization.getStatus())));
    }

    // ------------------------------------------------------------------ create

    @Test
    void theFounderBecomesOwnerOfAPendingOrganizationAfterTheLimitCheckUnderTheLock() {
        OrganizationResponse created = service.create(FOUNDER, "acme-store", "Acme Store");

        assertThat(created.status()).isEqualTo(OrganizationStatus.PENDING_APPROVAL);
        assertThat(created.myRole()).isEqualTo(OrgRole.OWNER);
        ArgumentCaptor<OrganizationMember> founder = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(members).save(founder.capture());
        assertThat(founder.getValue().getOrganizationId()).isEqualTo(created.id());
        assertThat(founder.getValue().getRole()).isEqualTo(OrgRole.OWNER);
        InOrder order = inOrder(organizations, members);
        order.verify(organizations).lockCreationFor(OrganizationService.CREATION_LOCK_NAMESPACE, FOUNDER.hashCode());
        order.verify(members).countOpenOwnedBy(FOUNDER);
        order.verify(organizations).saveAndFlush(any());
    }

    @Test
    void anAccountThatIsNoLongerActiveCannotCreate() {
        when(users.isActive(FOUNDER)).thenReturn(false);

        assertCode(() -> service.create(FOUNDER, "acme-store", "Acme"), ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(organizations, members);
    }

    @Test
    void reservedSlugsAreRejectedBeforeAnyLock() {
        assertThatThrownBy(() -> service.create(FOUNDER, "support", "Support"))
                .isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(organizations, members);
    }

    @Test
    void theOwnedOrganizationLimitIsEnforced() {
        when(members.countOpenOwnedBy(FOUNDER)).thenReturn(3L);

        assertCode(() -> service.create(FOUNDER, "acme-store", "Acme"), ErrorCode.ORG_LIMIT_REACHED);
        verify(organizations, never()).saveAndFlush(any());
    }

    @Test
    void aTakenSlugIs409WhetherSeenFirstOrLostInARace() {
        when(organizations.existsBySlug("acme-store")).thenReturn(true);
        assertCode(() -> service.create(FOUNDER, "acme-store", "Acme"), ErrorCode.ORG_SLUG_TAKEN);

        when(organizations.existsBySlug("acme-store")).thenReturn(false);
        when(organizations.saveAndFlush(any(Organization.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key violates uk_organizations_slug"));
        assertCode(() -> service.create(FOUNDER, "acme-store", "Acme"), ErrorCode.ORG_SLUG_TAKEN);
        verify(members, never()).save(any());
    }

    @Test
    void otherIntegrityErrorsAreNotDisguisedAsATakenSlug() {
        when(organizations.saveAndFlush(any(Organization.class)))
                .thenThrow(new DataIntegrityViolationException("violates fk_organizations_created_by"));

        assertThatThrownBy(() -> service.create(FOUNDER, "acme-store", "Acme"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ read and update

    @Test
    void outsidersGet404AndMembersSeeTheirRole() {
        UUID staff = UUID.randomUUID();
        Organization organization = existing(staff, OrgRole.STAFF);

        assertThatThrownBy(() -> service.get(UUID.randomUUID(), organization.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.get(staff, organization.getId()).myRole()).isEqualTo(OrgRole.STAFF);
    }

    @Test
    void staffCannotRenameButManagersCan() {
        UUID staff = UUID.randomUUID();
        Organization organization = existing(staff, OrgRole.STAFF);
        assertCode(() -> service.update(staff, organization.getId(), "New", 0), ErrorCode.FORBIDDEN);

        UUID manager = UUID.randomUUID();
        stubMembership(organization, manager, OrgRole.MANAGER);
        assertThat(service.update(manager, organization.getId(), "New name", 0).name()).isEqualTo("New name");
        verify(organizations).flush();
    }

    @Test
    void aStaleVersionIsRejectedWithoutChangingAnything() {
        Organization organization = existing(FOUNDER, OrgRole.OWNER);

        assertCode(() -> service.update(FOUNDER, organization.getId(), "New", 7), ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(organization.getName()).isEqualTo("Acme");
    }

    @Test
    void suspendedAndClosedOrganizationsAreReadOnly() {
        Organization organization = existing(FOUNDER, OrgRole.OWNER);
        organization.approve(UUID.randomUUID(), NOW);
        organization.suspend("Fraud", UUID.randomUUID(), NOW);
        assertCode(() -> service.update(FOUNDER, organization.getId(), "New", 0), ErrorCode.ORG_INVALID_STATUS);

        organization.close(FOUNDER, NOW);
        assertCode(() -> service.update(FOUNDER, organization.getId(), "New", 0), ErrorCode.ORG_INVALID_STATUS);
        verify(organizations, never()).flush();
    }

    @Test
    void theMembershipCheckIsOneQuery() {
        Organization organization = existing(FOUNDER, OrgRole.OWNER);

        service.get(FOUNDER, organization.getId());

        verify(members).findMembership(organization.getId(), FOUNDER);
        verify(members, never()).findById(any());
        verify(members, never()).findMemberships(any(), any());
        verify(organizations, never()).lockCreationFor(anyInt(), anyInt());
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
