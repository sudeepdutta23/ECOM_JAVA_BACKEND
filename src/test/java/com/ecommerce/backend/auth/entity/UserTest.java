package com.ecommerce.backend.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

        user.deactivate(NOW);

        assertThat(user.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void deactivationRecordsTheOwnerAsTheChanger() {
        User user = activeUser();

        user.deactivate(NOW);

        assertThat(user.getStatusChangedBy()).isEqualTo(user.getId());
        assertThat(user.getStatusChangedAt()).isEqualTo(NOW);
        assertThat(user.getStatusReason()).isNull();
    }

    @Test
    void suspensionRecordsWhoWhenAndWhy() {
        User user = activeUser();
        UUID admin = UUID.randomUUID();

        user.suspend("  Chargeback fraud  ", admin, NOW);

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(user.isActive()).isFalse();
        assertThat(user.getStatusReason()).isEqualTo("Chargeback fraud");
        assertThat(user.getStatusChangedBy()).isEqualTo(admin);
        assertThat(user.getStatusChangedAt()).isEqualTo(NOW);
    }

    @Test
    void onlyActiveAccountsCanBeSuspended() {
        User pending = User.register("p@example.com", "{argon2}hash", NOW);
        User suspended = activeUser();
        suspended.suspend("Abuse", UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> pending.suspend("Abuse", UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> suspended.suspend("Again", UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSuspensionNeedsAReason() {
        assertThatThrownBy(() -> activeUser().suspend("   ", UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reactivationReopensSuspendedAndDeactivatedAccountsAndClearsTheReason() {
        User suspended = activeUser();
        suspended.suspend("Abuse", UUID.randomUUID(), NOW);
        User deactivated = activeUser();
        deactivated.deactivate(NOW);
        UUID admin = UUID.randomUUID();
        Instant later = NOW.plusSeconds(60);

        suspended.reactivate(admin, later);
        deactivated.reactivate(admin, later);

        assertThat(suspended.isActive()).isTrue();
        assertThat(suspended.getStatusReason()).isNull();
        assertThat(suspended.getStatusChangedBy()).isEqualTo(admin);
        assertThat(deactivated.isActive()).isTrue();
    }

    @Test
    void activeAndPendingAccountsCannotBeReactivated() {
        assertThatThrownBy(() -> activeUser().reactivate(UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> User.register("p@example.com", "{argon2}hash", NOW).reactivate(UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void erasureRemovesEveryPersonalFieldAndKeepsTheRow() {
        User user = activeUser();
        user.recordSuccessfulLogin(NOW);
        user.suspend("Fraud, see ticket about J. Doe", UUID.randomUUID(), NOW);
        UUID admin = UUID.randomUUID();
        Instant later = NOW.plusSeconds(60);

        user.erase(admin, later);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ERASED);
        assertThat(user.getEmail()).isEqualTo("erased-" + user.getId() + "@erased.invalid");
        assertThat(user.getPasswordHash()).isEqualTo("{erased}");
        assertThat(user.getEmailVerifiedAt()).isNull();
        assertThat(user.getLastLoginAt()).isNull();
        assertThat(user.getStatusReason()).isNull();
        assertThat(user.getStatusChangedBy()).isEqualTo(admin);
        assertThat(user.getStatusChangedAt()).isEqualTo(later);
        // The placeholder is already normalized, as ck_users_email_normalized requires.
        assertThat(User.normalizeEmail(user.getEmail())).isEqualTo(user.getEmail());
    }

    @Test
    void erasureIsTerminal() {
        User user = activeUser();
        user.erase(user.getId(), NOW);

        assertThatThrownBy(() -> user.erase(user.getId(), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> user.reactivate(UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> user.suspend("x", UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> user.verifyEmail(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pendingAccountsCanBeErased() {
        User pending = User.register("p@example.com", "{argon2}hash", NOW);
        ReflectionTestUtils.setField(pending, "id", UUID.randomUUID());

        pending.erase(UUID.randomUUID(), NOW);

        assertThat(pending.isErased()).isTrue();
    }

    private static User activeUser() {
        User user = User.register("a@example.com", "{argon2}hash", NOW);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.verifyEmail(NOW);
        return user;
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
