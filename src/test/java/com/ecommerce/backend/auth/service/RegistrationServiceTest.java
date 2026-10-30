package com.ecommerce.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.auth.entity.TokenPurpose;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.event.DuplicateRegistrationAttempted;
import com.ecommerce.backend.auth.event.VerificationEmailRequested;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.RequestValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Pure unit test: no Spring, no database. Collaborators are mocks; transactions are replaced by
 * {@link TransactionOperations#withoutTransaction()}, which simply runs the callback.
 */
class RegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final String PASSWORD = "Correct-Horse-9-Battery";

    private final UserRepository users = mock(UserRepository.class);
    private final AccountTokenService accountTokens = mock(AccountTokenService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RegistrationService service = new RegistrationService(
            users, accountTokens, new PasswordPolicy(), encoder, events,
            TransactionOperations.withoutTransaction(),
            TestAuthProperties.defaults(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    {
        when(encoder.encode(anyString())).thenReturn("{argon2}encoded");
        when(accountTokens.issue(any(), eq(TokenPurpose.EMAIL_VERIFICATION), any())).thenReturn("raw-token");
        when(users.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void newEmailCreatesPendingUserAndRequestsVerificationEmail() {
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.empty());

        service.register("  Alice@Example.com ", PASSWORD);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{argon2}encoded");
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        verify(events).publishEvent(new VerificationEmailRequested("alice@example.com", "raw-token"));
    }

    @Test
    void existingActiveAccountGetsNoticeInsteadOfNewAccount() {
        User active = User.register("alice@example.com", "{argon2}old", NOW);
        active.verifyEmail(NOW);
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(active));

        service.register("alice@example.com", PASSWORD);

        verify(users, never()).saveAndFlush(any());
        verify(events).publishEvent(new DuplicateRegistrationAttempted("alice@example.com"));
        assertThat(active.getPasswordHash()).isEqualTo("{argon2}old"); // never overwritten
    }

    @Test
    void existingPendingAccountGetsAFreshVerificationLink() {
        User pending = User.register("alice@example.com", "{argon2}old", NOW);
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(pending));

        service.register("alice@example.com", PASSWORD);

        verify(users, never()).saveAndFlush(any());
        verify(accountTokens).issue(pending, TokenPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));
        verify(events).publishEvent(any(VerificationEmailRequested.class));
    }

    @Test
    void passwordIsHashedEvenForExistingEmailsSoTimingMatches() {
        User active = User.register("alice@example.com", "{argon2}old", NOW);
        active.verifyEmail(NOW);
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(active));

        service.register("alice@example.com", PASSWORD);

        verify(encoder).encode(PASSWORD);
    }

    @Test
    void weakPasswordIsRejectedBeforeAnyWork() {
        assertThatThrownBy(() -> service.register("alice@example.com", "Password1234"))
                .isInstanceOf(RequestValidationException.class);

        verifyNoInteractions(users, encoder, events);
    }

    @Test
    void concurrentDuplicateIsSwallowedSoTheResponseStaysTheSame() {
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatCode(() -> service.register("alice@example.com", PASSWORD)).doesNotThrowAnyException();
    }

    @Test
    void verifyEmailActivatesPendingUser() {
        User pending = User.register("alice@example.com", "{argon2}h", NOW);
        when(accountTokens.consume("raw-token", TokenPurpose.EMAIL_VERIFICATION)).thenReturn(pending);

        service.verifyEmail("raw-token");

        assertThat(pending.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(pending.getEmailVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void verifyEmailRejectsDeactivatedUser() {
        User deactivated = User.register("alice@example.com", "{argon2}h", NOW);
        deactivated.deactivate();
        when(accountTokens.consume("raw-token", TokenPurpose.EMAIL_VERIFICATION)).thenReturn(deactivated);

        assertThatThrownBy(() -> service.verifyEmail("raw-token")).isInstanceOf(BusinessException.class);
    }

    @Test
    void resendForPendingUserIssuesNewLink() {
        User pending = User.register("alice@example.com", "{argon2}h", NOW);
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(pending));

        service.resendVerification("ALICE@example.com");

        verify(events).publishEvent(any(VerificationEmailRequested.class));
    }

    @Test
    void resendForUnknownOrActiveEmailDoesNothingVisible() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.resendVerification("nobody@example.com");

        verifyNoInteractions(accountTokens, events);
    }
}
