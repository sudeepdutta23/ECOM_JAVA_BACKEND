package com.ecommerce.backend.org.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.org.OrganizationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrganizationTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final UUID FOUNDER = UUID.randomUUID();
    private static final UUID PLATFORM_ADMIN = UUID.randomUUID();

    private static Organization pending() {
        return Organization.create("acme-store", "Acme Store", FOUNDER, NOW);
    }

    private static Organization active() {
        Organization organization = pending();
        organization.approve(PLATFORM_ADMIN, NOW);
        return organization;
    }

    @Test
    void aNewOrganizationWaitsForApprovalAndRecordsItsFounder() {
        Organization organization = Organization.create("acme-store", "  Acme Store ", FOUNDER, NOW);

        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.PENDING_APPROVAL);
        assertThat(organization.getName()).isEqualTo("Acme Store");
        assertThat(organization.getCreatedBy()).isEqualTo(FOUNDER);
        assertThat(organization.getStatusChangedBy()).isEqualTo(FOUNDER);
        assertThat(organization.isActive()).isFalse();
    }

    @Test
    void slugsAreNormalizedWithAFixedLocale() {
        assertThat(Organization.normalizeSlug("  ACME-Store ")).isEqualTo("acme-store");
        assertThat(Organization.normalizeSlug("ISTANBUL")).isEqualTo("istanbul"); // not the dotless Turkish ı
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "-acme", "acme-", "Acme", "acme store", "acme_store", "ac.me",
            "a123456789012345678901234567890123456789012345678901"})
    void invalidSlugsAreRejected(String slug) {
        assertThatThrownBy(() -> Organization.create(slug, "Acme", FOUNDER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "acme-store", "3d-prints", "a1234567890123456789012345678901234567890123456789"})
    void validSlugsAreAccepted(String slug) {
        assertThat(Organization.create(slug, "Acme", FOUNDER, NOW).getSlug()).isEqualTo(slug);
    }

    @Test
    void aNameIsRequired() {
        assertThatThrownBy(() -> Organization.create("acme", "   ", FOUNDER, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending().rename(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalActivatesOnlyAPendingOrganization() {
        Organization organization = active();

        assertThat(organization.isActive()).isTrue();
        assertThat(organization.getStatusChangedBy()).isEqualTo(PLATFORM_ADMIN);
        assertThatThrownBy(() -> organization.approve(PLATFORM_ADMIN, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void suspensionNeedsAnActiveOrganizationAndAReasonAndReactivationClearsIt() {
        assertThatThrownBy(() -> pending().suspend("Fraud", PLATFORM_ADMIN, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> active().suspend("  ", PLATFORM_ADMIN, NOW)).isInstanceOf(IllegalArgumentException.class);

        Organization organization = active();
        organization.suspend(" Counterfeit goods ", PLATFORM_ADMIN, NOW);
        assertThat(organization.getStatus()).isEqualTo(OrganizationStatus.SUSPENDED);
        assertThat(organization.getStatusReason()).isEqualTo("Counterfeit goods");

        organization.reactivate(PLATFORM_ADMIN, NOW.plusSeconds(60));
        assertThat(organization.isActive()).isTrue();
        assertThat(organization.getStatusReason()).isNull();
    }

    @Test
    void closingIsPossibleFromEveryStatusAndTerminal() {
        Organization suspended = active();
        suspended.suspend("Fraud", PLATFORM_ADMIN, NOW);
        for (Organization organization : new Organization[] {pending(), active(), suspended}) {
            organization.close(FOUNDER, NOW);
            assertThat(organization.isClosed()).isTrue();
            assertThat(organization.getStatusReason()).isNull();
        }

        Organization closed = pending();
        closed.close(FOUNDER, NOW);
        assertThatThrownBy(() -> closed.close(FOUNDER, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.approve(PLATFORM_ADMIN, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.reactivate(PLATFORM_ADMIN, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.rename("New name")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toStringNeverContainsTheReason() {
        Organization organization = active();
        organization.suspend("Complaint by J. Doe", PLATFORM_ADMIN, NOW);

        assertThat(organization.toString()).doesNotContain("Doe").contains("acme-store");
    }
}
