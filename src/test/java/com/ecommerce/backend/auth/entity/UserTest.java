package com.ecommerce.backend.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void registerNormalizesEmailAndStartsPending() {
        User user = User.register("  Alice@Example.COM ", "{argon2}hash", NOW);

        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getPasswordChangedAt()).isEqualTo(NOW);
    }

    @Test
    void normalizationIgnoresDefaultLocale() {
        // In a Turkish locale, "I".toLowerCase() is a dotless "ı"; Locale.ROOT avoids that.
        assertThat(User.normalizeEmail("ADMIN@SITE.IO")).isEqualTo("admin@site.io");
    }

    @Test
    void verifyEmailActivatesAccount() {
        User user = User.register("a@example.com", "{argon2}hash", NOW);

        user.verifyEmail(NOW);

        assertThat(user.isActive()).isTrue();
        assertThat(user.getEmailVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void verifyingTwiceIsRejected() {
        User user = User.register("a@example.com", "{argon2}hash", NOW);
        user.verifyEmail(NOW);

        assertThatThrownBy(() -> user.verifyEmail(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void newUserIsNotLocked() {
        User user = User.register("a@example.com", "{argon2}hash", NOW);

        assertThat(user.isLockedAt(NOW)).isFalse();
    }

    @Test
    void successfulLoginRecordsTimestamp() {
        User user = User.register("a@example.com", "{argon2}hash", NOW);
        Instant later = NOW.plus(Duration.ofMinutes(5));

        user.recordSuccessfulLogin(later);

        assertThat(user.getLastLoginAt()).isEqualTo(later);
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void changePasswordUpdatesHashAndTimestamp() {
        User user = User.register("a@example.com", "{argon2}old", NOW);
        Instant later = NOW.plus(Duration.ofDays(1));

        user.changePassword("{argon2}new", later);

        assertThat(user.getPasswordHash()).isEqualTo("{argon2}new");
        assertThat(user.getPasswordChangedAt()).isEqualTo(later);
    }

    @Test
    void deactivateChangesStatus() {
        User user = User.register("a@example.com", "{argon2}hash", NOW);

        user.deactivate();

        assertThat(user.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void toStringNeverContainsSecretsOrEmail() {
        User user = User.register("secret.person@example.com", "{argon2}super-secret-hash", NOW);

        assertThat(user.toString())
                .doesNotContain("super-secret-hash")
                .doesNotContain("secret.person");
    }

    @Test
    void unsavedUsersAreNotEqual() {
        User a = User.register("a@example.com", "{argon2}hash", NOW);
        User b = User.register("a@example.com", "{argon2}hash", NOW);

        assertThat(a).isNotEqualTo(b).isEqualTo(a);
    }
}
