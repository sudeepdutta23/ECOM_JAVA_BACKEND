package com.ecommerce.backend.org.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.dto.AdminOrganizationDetail;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.entity.OrganizationMember;
import com.ecommerce.backend.org.repository.OrganizationInvitationRepository;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.repository.OrganizationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrganizationAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final UUID STAFF = UUID.randomUUID();

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final OrganizationInvitationRepository invitations = mock(OrganizationInvitationRepository.class);
    private final OrganizationAdminService service = new OrganizationAdminService(organizations, members, invitations,
            mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));

    private Organization organization;

    @BeforeEach
    void setUp() {
        organization = Organization.create("acme-store", "Acme", UUID.randomUUID(), NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(organization, "id", UUID.randomUUID());
        when(organizations.findByIdForUpdate(organization.getId())).thenReturn(Optional.of(organization));
        when(organizations.findById(organization.getId())).thenReturn(Optional.of(organization));
    }

    @Test
    void approvalActivatesAPendingOrganizationAndRecordsWhoDidIt() {
        AdminOrganizationDetail detail = service.approve(STAFF, organization.getId());

        assertThat(detail.status()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(detail.statusChangedBy()).isEqualTo(STAFF);
    }

    @Test
    void staffCannotActOnAnOrganizationTheyBelongTo() {
        when(members.existsById(new OrganizationMember.Key(organization.getId(), STAFF))).thenReturn(true);

        assertCode(() -> service.approve(STAFF, organization.getId()), ErrorCode.ORG_CONFLICT_OF_INTEREST);
        assertCode(() -> service.close(STAFF, organization.getId()), ErrorCode.ORG_CONFLICT_OF_INTEREST);
        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.PENDING_APPROVAL);
    }

    @Test
    void eachTransitionNeedsItsStatus() {
        assertCode(() -> service.suspend(STAFF, organization.getId(), "Fraud"), ErrorCode.ORG_INVALID_STATUS);
        assertCode(() -> service.reactivate(STAFF, organization.getId()), ErrorCode.ORG_INVALID_STATUS);

        service.approve(STAFF, organization.getId());
        assertCode(() -> service.approve(STAFF, organization.getId()), ErrorCode.ORG_INVALID_STATUS);
        assertThat(service.suspend(STAFF, organization.getId(), "Counterfeit goods").statusReason())
                .isEqualTo("Counterfeit goods");
        assertThat(service.reactivate(STAFF, organization.getId()).statusReason()).isNull();
    }

    @Test
    void closingRevokesOpenInvitationsAndHappensOnce() {
        service.close(STAFF, organization.getId());

        assertThat(organization.isClosed()).isTrue();
        verify(invitations).revokeAllOpen(organization.getId(), NOW);
        assertCode(() -> service.close(STAFF, organization.getId()), ErrorCode.ORG_INVALID_STATUS);
    }

    @Test
    void aRefusedActionTouchesNoInvitations() {
        when(members.existsById(any())).thenReturn(true);

        assertCode(() -> service.close(STAFF, organization.getId()), ErrorCode.ORG_CONFLICT_OF_INTEREST);
        verify(invitations, never()).revokeAllOpen(any(), any());
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
