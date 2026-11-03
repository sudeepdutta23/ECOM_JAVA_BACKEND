package com.ecommerce.backend.org.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.service.OrganizationGuard.Intent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The gate every seller endpoint relies on: membership, then permission, then status. */
class OrganizationGuardTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final OrganizationGuard guard = new OrganizationGuard(members);

    private void membership(OrgRole role, OrganizationStatus status) {
        when(members.findMembership(ORG, USER)).thenReturn(Optional.of(new OrgMembership(ORG, USER, role, status)));
    }

    @Test
    void aNonMemberGetsTheSame404AsForAnOrganizationThatDoesNotExist() {
        assertThatThrownBy(() -> guard.require(ORG, USER, OrgPermission.ORGANIZATION_READ, Intent.READ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Organization not found");
    }

    @Test
    void aMemberWithoutThePermissionGets403EvenBeforeTheStatusIsConsidered() {
        membership(OrgRole.STAFF, OrganizationStatus.SUSPENDED);

        assertCode(() -> guard.require(ORG, USER, OrgPermission.ORGANIZATION_UPDATE, Intent.WRITE), ErrorCode.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(OrganizationStatus.class)
    void readingIsAllowedInEveryStatus(OrganizationStatus status) {
        membership(OrgRole.STAFF, status);

        assertThat(guard.require(ORG, USER, OrgPermission.ORGANIZATION_READ, Intent.READ).role()).isEqualTo(OrgRole.STAFF);
    }

    @ParameterizedTest
    @EnumSource(value = OrganizationStatus.class, names = {"PENDING_APPROVAL", "ACTIVE"})
    void writingIsAllowedWhilePendingOrActive(OrganizationStatus status) {
        membership(OrgRole.OWNER, status);

        assertThat(guard.require(ORG, USER, OrgPermission.ORGANIZATION_UPDATE, Intent.WRITE).organizationStatus())
                .isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = OrganizationStatus.class, names = {"SUSPENDED", "CLOSED"})
    void writingIsRefusedWhileSuspendedOrClosed(OrganizationStatus status) {
        membership(OrgRole.OWNER, status);

        assertCode(() -> guard.require(ORG, USER, OrgPermission.ORGANIZATION_UPDATE, Intent.WRITE),
                ErrorCode.ORG_INVALID_STATUS);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ------------------------------------------------------------------ requireForSellerWrite

    @Test
    void sellerWritesDecideOnTheLockedStatusNotOnTheUnlockedRead() {
        membership(OrgRole.STAFF, OrganizationStatus.ACTIVE);          // read without a lock
        when(members.lockOrganizationStatus(ORG)).thenReturn(Optional.of("SUSPENDED")); // suspended meanwhile

        assertCode(() -> guard.requireForSellerWrite(ORG, USER, OrgPermission.CATALOG_MANAGE), ErrorCode.ORG_INVALID_STATUS);
    }

    @Test
    void sellerWritesReturnTheLockedStatus() {
        membership(OrgRole.STAFF, OrganizationStatus.PENDING_APPROVAL);
        when(members.lockOrganizationStatus(ORG)).thenReturn(Optional.of("ACTIVE")); // approved meanwhile

        assertThat(guard.requireForSellerWrite(ORG, USER, OrgPermission.CATALOG_MANAGE).organizationStatus())
                .isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    void sellerWritesCheckMembershipAndPermissionBeforeLocking() {
        assertThatThrownBy(() -> guard.requireForSellerWrite(ORG, USER, OrgPermission.CATALOG_MANAGE))
                .isInstanceOf(ResourceNotFoundException.class);
        membership(OrgRole.STAFF, OrganizationStatus.ACTIVE);
        assertCode(() -> guard.requireForSellerWrite(ORG, USER, OrgPermission.MEMBER_MANAGE), ErrorCode.FORBIDDEN);
        org.mockito.Mockito.verify(members, org.mockito.Mockito.never()).lockOrganizationStatus(ORG);
    }
}
