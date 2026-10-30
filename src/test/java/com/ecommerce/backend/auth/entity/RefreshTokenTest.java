package com.ecommerce.backend.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private final User user = User.register("a@example.com", "{argon2}hash", NOW);

    private RefreshToken newToken() {
        return RefreshToken.issue(user, UUID.randomUUID(), "a".repeat(64), NOW, TTL, "127.0.0.1", "JUnit");
    }

    @Test
    void issuedTokenIsActiveUntilExpiry() {
        RefreshToken token = newToken();

        assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(token.isActiveAt(NOW)).isTrue();
        assertThat(token.isActiveAt(NOW.plus(TTL))).isFalse();
    }

    @Test
    void revokedTokenIsInactive() {
        RefreshToken token = newToken();

        token.revoke(RevokeReason.LOGOUT, NOW);

        assertThat(token.isRevoked()).isTrue();
        assertThat(token.isActiveAt(NOW)).isFalse();
        assertThat(token.getRevokedAt()).isEqualTo(NOW);
    }

    @Test
    void revokeKeepsTheFirstReason() {
        RefreshToken token = newToken();
        token.revoke(RevokeReason.ROTATED, NOW);

        token.revoke(RevokeReason.REUSE_DETECTED, NOW.plusSeconds(60));

        assertThat(token.getRevokeReason()).isEqualTo(RevokeReason.ROTATED);
        assertThat(token.getRevokedAt()).isEqualTo(NOW);
    }

    @Test
    void markRotatedLinksSuccessor() {
        RefreshToken token = newToken();
        UUID successor = UUID.randomUUID();

        token.markRotated(successor, NOW);

        assertThat(token.getRevokeReason()).isEqualTo(RevokeReason.ROTATED);
        assertThat(token.getReplacedById()).isEqualTo(successor);
    }

    @Test
    void oversizedClientHeadersAreTruncated() {
        String hugeAgent = "x".repeat(1000);

        RefreshToken token = RefreshToken.issue(user, UUID.randomUUID(), "a".repeat(64), NOW, TTL,
                "1".repeat(100), hugeAgent);

        assertThat(token.getUserAgent()).hasSize(RefreshToken.MAX_USER_AGENT_LENGTH);
        assertThat(token.getCreatedIp()).hasSize(RefreshToken.MAX_IP_LENGTH);
    }
}
