package com.ecommerce.backend.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountTokenTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private final User user = User.register("a@example.com", "{argon2}hash", NOW);

    @Test
    void tokenIsUsableBeforeExpiry() {
        AccountToken token = AccountToken.issue(user, TokenPurpose.PASSWORD_RESET, "a".repeat(64), NOW,
                Duration.ofMinutes(30));

        assertThat(token.getCreatedAt()).isEqualTo(NOW);
        assertThat(token.isUsableAt(NOW.plusSeconds(60))).isTrue();
        assertThat(token.isUsableAt(NOW.plus(Duration.ofMinutes(30)))).isFalse();
    }

    @Test
    void consumedTokenIsNotUsable() {
        AccountToken token = AccountToken.issue(user, TokenPurpose.EMAIL_VERIFICATION, "a".repeat(64), NOW,
                Duration.ofHours(24));

        token.consume(NOW);

        assertThat(token.isUsableAt(NOW)).isFalse();
        assertThat(token.getConsumedAt()).isEqualTo(NOW);
    }

    @Test
    void tokenCannotBeConsumedTwice() {
        AccountToken token = AccountToken.issue(user, TokenPurpose.EMAIL_VERIFICATION, "a".repeat(64), NOW,
                Duration.ofHours(24));
        token.consume(NOW);

        assertThatThrownBy(() -> token.consume(NOW)).isInstanceOf(IllegalStateException.class);
    }
}
