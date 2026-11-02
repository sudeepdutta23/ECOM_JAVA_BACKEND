package com.ecommerce.backend.org.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.repository.OrganizationInvitationRepository;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.repository.OrganizationRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemberDepartureTest {

    private static final UUID USER = UUID.randomUUID();

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final OrganizationInvitationRepository invitations = mock(OrganizationInvitationRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final MemberDeparture departure = new MemberDeparture(organizations, members, invitations, users);

    /** An organisation the user owns, with this many OTHER owners whose accounts are active. */
    private Organization owned(String slug, long otherActiveOwners) {
        Organization organization = Organization.create(slug, "Shop", USER, Instant.now());
        ReflectionTestUtils.setField(organization, "id", UUID.randomUUID());
        UUID other = UUID.randomUUID();
        when(members.findOwnerIds(organization.getId())).thenReturn(List.of(USER, other));
        when(users.countActive(List.of(other))).thenReturn(otherActiveOwners);
        return organization;
    }

    @Test
    void theOnlyOwnerOfAnOpenOrganizationCannotLeaveAndIsToldWhich() {
        // Built first: owned() stubs a mock, which Mockito forbids inside another when(...).
        List<Organization> owned = List.of(owned("acme-store", 0), owned("co-owned", 1), owned("beta-shop", 0));
        when(organizations.lockOpenOwnedBy(USER)).thenReturn(owned);

        assertThatThrownBy(() -> departure.requireNotSoleOwner(USER)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORG_LAST_OWNER);
            assertThat(e.getMessage()).contains("acme-store, beta-shop").doesNotContain("co-owned").contains("them");
        });
    }

    @Test
    void coOwnedOrNoOrganizationsAllowLeaving() {
        List<Organization> coOwned = List.of(owned("co-owned", 1));
        when(organizations.lockOpenOwnedBy(USER)).thenReturn(coOwned);
        assertThatCode(() -> departure.requireNotSoleOwner(USER)).doesNotThrowAnyException();

        when(organizations.lockOpenOwnedBy(USER)).thenReturn(List.of());
        assertThatCode(() -> departure.requireNotSoleOwner(USER)).doesNotThrowAnyException();
    }

    @Test
    void forgettingRemovesMembershipsAndEveryInvitationToTheAddress() {
        when(members.deleteAllByUserId(USER)).thenReturn(2);
        when(invitations.deleteAllByEmail("bob@example.com")).thenReturn(3);

        assertThat(departure.forget(USER, "bob@example.com")).isEqualTo(new MemberDeparture.Removed(2, 3));
    }
}
