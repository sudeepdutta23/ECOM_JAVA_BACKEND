package com.ecommerce.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.TokenPurpose;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.event.PasswordChanged;
import com.ecommerce.backend.auth.event.PasswordResetRequested;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.auth.security.AccessToken;
import com.ecommerce.backend.auth.security.AccessTokenIssuer;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

class PasswordServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final String EMAIL = "alice@example.com";
    private static final String OLD = "Correct-Horse-9-Battery";
    private static final String NEW = "Even-Better-Passphrase-42";
    private static final String OLD_HASH = "{argon2}old";
    private static final String RESET_TOKEN = "q2vQ8m1f0xY3bS9kLw7pT4rN6uC5aE2dH8jK1zR0sVg";
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "JUnit");

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final AccountTokenService accountTokens = mock(AccountTokenService.class);
    private final AccessTokenIssuer accessTokens = mock(AccessTokenIssuer.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private PasswordService service;
    private User user;

    @BeforeEach
    void setUp() {
        TransactionOperations noTx = TransactionOperations.withoutTransaction();
        AuthProperties props = TestAuthProperties.defaults();
        CurrentPasswordCheck check = new CurrentPasswordCheck(encoder, new FailedLoginRecorder(users, events, noTx, props));
        service = new PasswordService(users, check, new PasswordPolicy(), encoder, refreshTokens, accountTokens,
                accessTokens, events, noTx, props, Clock.fixed(NOW, ZoneOffset.UTC));

        user = User.register(EMAIL, OLD_HASH, NOW.minus(Duration.ofDays(30)));
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.verifyEmail(NOW.minus(Duration.ofDays(30)));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.getReferenceById(user.getId())).thenReturn(user);
        when(encoder.matches(OLD, OLD_HASH)).thenReturn(true);
        when(encoder.encode(NEW)).thenReturn("{argon2}new");
        when(accessTokens.issue(user.getId())).thenReturn(new AccessToken("jwt", NOW.plusSeconds(900), 900));
        when(refreshTokens.issueForNewLogin(any(), any())).thenReturn(new IssuedRefreshToken("fresh", NOW.plusSeconds(60)));
    }

    // ------------------------------------------------------------------ change

    @Test
    void changeSetsNewHashEndsAllSessionsAndReturnsAFreshOne() {
        LoginResult result = service.changePassword(user.getId(), OLD, NEW, CLIENT);

        assertThat(user.getPasswordHash()).isEqualTo("{argon2}new");
        assertThat(user.getPasswordChangedAt()).isEqualTo(NOW);
        verify(refreshTokens).revokeAll(user.getId(), RevokeReason.PASSWORD_CHANGED);
        verify(refreshTokens).issueForNewLogin(user, CLIENT);
        verify(events).publishEvent(new PasswordChanged(EMAIL));
        assertThat(result.refreshToken().rawValue()).isEqualTo("fresh");
    }

    @Test
    void wrongCurrentPasswordIsRejectedAndCounted() {
        when(encoder.matches("not-it", OLD_HASH)).thenReturn(false);

        assertCode(() -> service.changePassword(user.getId(), "not-it", NEW, CLIENT), ErrorCode.AUTH_INVALID_CURRENT_PASSWORD);

        verify(users).registerFailedLogin(eq(user.getId()), eq(5), any(), eq(NOW));
        verify(refreshTokens, never()).revokeAll(any(), any());
    }

    @Test
    void lockedAccountCannotChangePasswordEvenWithTheRightOne() {
        ReflectionTestUtils.setField(user, "lockedUntil", NOW.plusSeconds(60));

        assertCode(() -> service.changePassword(user.getId(), OLD, NEW, CLIENT), ErrorCode.AUTH_INVALID_CURRENT_PASSWORD);
        verify(encoder, never()).matches(OLD, OLD_HASH);
    }

    @Test
    void weakNewPasswordIsRejectedOnTheNewPasswordField() {
        assertThatThrownBy(() -> service.changePassword(user.getId(), OLD, "Password1234", CLIENT))
                .isInstanceOfSatisfying(RequestValidationException.class,
                        e -> assertThat(e.getErrors().get(0).field()).isEqualTo("newPassword"));
        verify(refreshTokens, never()).revokeAll(any(), any());
    }

    @Test
    void newPasswordMustDifferFromTheCurrentOne() {
        when(encoder.matches(NEW, OLD_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(user.getId(), OLD, NEW, CLIENT))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void deactivatedAccountCannotChangePassword() {
        user.deactivate();

        assertCode(() -> service.changePassword(user.getId(), OLD, NEW, CLIENT), ErrorCode.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ forgot

    @Test
    void forgotIssuesResetLinkForActiveAccount() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(accountTokens.issue(user, TokenPurpose.PASSWORD_RESET, Duration.ofMinutes(30))).thenReturn("raw-reset");

        service.forgotPassword("Alice@Example.com");

        verify(events).publishEvent(new PasswordResetRequested(EMAIL, "raw-reset"));
    }

    @Test
    void forgotIsSilentForUnknownAndInactiveAccounts() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        User pending = User.register("pending@example.com", OLD_HASH, NOW);
        when(users.findByEmail("pending@example.com")).thenReturn(Optional.of(pending));

        service.forgotPassword("nobody@example.com");
        service.forgotPassword("pending@example.com");

        verifyNoInteractions(accountTokens, events);
    }

    // ------------------------------------------------------------------ reset

    @Test
    void resetSetsPasswordClearsLockoutAndEndsAllSessions() {
        ReflectionTestUtils.setField(user, "failedLoginCount", 5);
        ReflectionTestUtils.setField(user, "lockedUntil", NOW.plusSeconds(600));
        when(accountTokens.peekUser(RESET_TOKEN, TokenPurpose.PASSWORD_RESET)).thenReturn(user);
        when(accountTokens.consume(RESET_TOKEN, TokenPurpose.PASSWORD_RESET)).thenReturn(user);

        service.resetPassword(RESET_TOKEN, NEW);

        assertThat(user.getPasswordHash()).isEqualTo("{argon2}new");
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getFailedLoginCount()).isZero();
        verify(refreshTokens).revokeAll(user.getId(), RevokeReason.PASSWORD_RESET);
        verify(events).publishEvent(new PasswordChanged(EMAIL));
    }

    @Test
    void weakPasswordOnResetDoesNotBurnTheToken() {
        when(accountTokens.peekUser(RESET_TOKEN, TokenPurpose.PASSWORD_RESET)).thenReturn(user);

        assertThatThrownBy(() -> service.resetPassword(RESET_TOKEN, "Password1234"))
                .isInstanceOf(RequestValidationException.class);

        verify(accountTokens, never()).consume(anyString(), any());
    }

    @Test
    void resetForAccountDeactivatedSinceTheEmailIsRejected() {
        when(accountTokens.peekUser(RESET_TOKEN, TokenPurpose.PASSWORD_RESET)).thenReturn(user);
        User deactivated = User.register(EMAIL, OLD_HASH, NOW);
        deactivated.deactivate();
        when(accountTokens.consume(RESET_TOKEN, TokenPurpose.PASSWORD_RESET)).thenReturn(deactivated);

        assertCode(() -> service.resetPassword(RESET_TOKEN, NEW), ErrorCode.AUTH_INVALID_ACCOUNT_TOKEN);
        assertThat(deactivated.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        verify(users, never()).registerFailedLogin(any(), anyInt(), any(), any());
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
