package com.ecommerce.backend.org.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.org.OrgRole;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationInvitationTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String HASH = "a".repeat(64);

    private static OrganizationInvitation invitation() {
        return OrganizationInvitation.issue(UUID.randomUUID(), "bob@example.com", OrgRole.STAFF, HASH,
                UUID.randomUUID(), NOW, Duration.ofDays(7));
    }

    @Test
    void anInvitationIsOpenUntilItExpires() {
        OrganizationInvitation invitation = invitation();

        assertThat(invitation.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(invitation.isOpenAt(NOW.plus(Duration.ofDays(7)).minusSeconds(1))).isTrue();
        assertThat(invitation.isOpenAt(NOW.plus(Duration.ofDays(7)))).isFalse();
    }

    @Test
    void itCanBeAcceptedOnceAndNotAfterRevocationOrExpiry() {
        OrganizationInvitation accepted = invitation();
        accepted.accept(NOW);
        assertThatThrownBy(() -> accepted.accept(NOW)).isInstanceOf(IllegalStateException.class);

        OrganizationInvitation revoked = invitation();
        revoked.revoke(NOW);
        assertThatThrownBy(() -> revoked.accept(NOW)).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> invitation().accept(NOW.plus(Duration.ofDays(8)))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokingIsIdempotentAndNeverUndoesAnAcceptance() {
        OrganizationInvitation accepted = invitation();
        accepted.accept(NOW);
        accepted.revoke(NOW.plusSeconds(1));
        assertThat(accepted.getRevokedAt()).isNull(); // the DB CHECK allows only one outcome

        OrganizationInvitation revoked = invitation();
        revoked.revoke(NOW);
        revoked.revoke(NOW.plusSeconds(5));
        assertThat(revoked.getRevokedAt()).isEqualTo(NOW);
    }

    @Test
    void emailsAreNormalizedAndNeverLogged() {
        assertThat(OrganizationInvitation.normalizeEmail("  Bob@Example.COM ")).isEqualTo("bob@example.com");
        assertThat(invitation().toString()).doesNotContain("bob").doesNotContain(HASH);
    }
}
