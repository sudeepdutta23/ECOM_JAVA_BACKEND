package com.ecommerce.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.event.AccountLocked;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.auth.security.AccessToken;
import com.ecommerce.backend.auth.security.AccessTokenIssuer;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
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

class AuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "Correct-Horse-9-Battery";
    private static final String REAL_HASH = "{argon2}real";
    private static final String DUMMY_HASH = "{argon2}dummy";
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "JUnit");

    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final AccessTokenIssuer accessTokens = mock(AccessTokenIssuer.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private AuthenticationService service;
    private User user;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn(DUMMY_HASH); // used for the dummy hash at construction
        TransactionOperations noTx = TransactionOperations.withoutTransaction();
        // The real recorder over the same mocks, so the lockout assertions below still apply.
        FailedLoginRecorder recorder = new FailedLoginRecorder(users, events, noTx, TestAuthProperties.defaults());
        service = new AuthenticationService(users, refreshTokens, accessTokens, encoder, recorder, noTx,
                TestAuthProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        user = User.register(EMAIL, REAL_HASH, NOW.minus(Duration.ofDays(30)));
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.verifyEmail(NOW.minus(Duration.ofDays(29)));
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.getReferenceById(user.getId())).thenReturn(user);
        when(accessTokens.issue(user.getId())).thenReturn(new AccessToken("jwt", NOW.plusSeconds(900), 900));
        when(refreshTokens.issueForNewLogin(user, CLIENT))
                .thenReturn(new IssuedRefreshToken("raw-refresh", NOW.plus(Duration.ofDays(7))));
    }

    @Test
    void correctPasswordIssuesBothTokensAndResetsCounter() {
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(true);

        LoginResult result = service.login("  ALICE@example.com ", PASSWORD, CLIENT);

        assertThat(result.accessToken().value()).isEqualTo("jwt");
        assertThat(result.refreshToken().rawValue()).isEqualTo("raw-refresh");
        verify(users).recordSuccessfulLogin(user.getId(), NOW);
        verify(users, never()).registerFailedLogin(any(), anyInt(), any(), any());
    }

    @Test
    void unknownEmailStillPaysForAHashCheck() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertCode(() -> service.login("nobody@example.com", PASSWORD, CLIENT), ErrorCode.AUTH_INVALID_CREDENTIALS);
        verify(encoder).matches(PASSWORD, DUMMY_HASH);
    }

    @Test
    void wrongPasswordIsCountedAtomically() {
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(false);

        assertCode(() -> service.login(EMAIL, PASSWORD, CLIENT), ErrorCode.AUTH_INVALID_CREDENTIALS);
        verify(users).registerFailedLogin(user.getId(), 5, NOW.plus(LOCKOUT), NOW);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void failureThatLocksTheAccountNotifiesTheOwner() {
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(false);
        // The re-read after the atomic update returns the row with OUR lock time in it.
        User lockedAfterUpdate = User.register(EMAIL, REAL_HASH, NOW);
        ReflectionTestUtils.setField(lockedAfterUpdate, "lockedUntil", NOW.plus(LOCKOUT));
        when(users.findById(user.getId())).thenReturn(Optional.of(lockedAfterUpdate));

        assertCode(() -> service.login(EMAIL, PASSWORD, CLIENT), ErrorCode.AUTH_INVALID_CREDENTIALS);
        verify(events).publishEvent(new AccountLocked(EMAIL, NOW.plus(LOCKOUT)));
    }

    @Test
    void lockedAccountNeverChecksTheRealPassword() {
        ReflectionTestUtils.setField(user, "lockedUntil", NOW.plusSeconds(60));

        assertCode(() -> service.login(EMAIL, PASSWORD, CLIENT), ErrorCode.AUTH_INVALID_CREDENTIALS);
        verify(encoder, never()).matches(PASSWORD, REAL_HASH);
        verify(encoder).matches(PASSWORD, DUMMY_HASH);
        verify(users, never()).registerFailedLogin(any(), anyInt(), any(), any());
    }

    @Test
    void expiredLockNoLongerBlocks() {
        ReflectionTestUtils.setField(user, "lockedUntil", NOW.minusSeconds(1));
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(true);

        assertThat(service.login(EMAIL, PASSWORD, CLIENT)).isNotNull();
    }

    @Test
    void unverifiedAccountGets403AfterCorrectPassword() {
        User pending = User.register(EMAIL, REAL_HASH, NOW);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(pending));
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(true);

        assertCode(() -> service.login(EMAIL, PASSWORD, CLIENT), ErrorCode.AUTH_EMAIL_NOT_VERIFIED);
    }

    @Test
    void unverifiedAccountWithWrongPasswordLooksLikeAnyWrongPassword() {
        User pending = User.register(EMAIL, REAL_HASH, NOW);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(pending));
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(false);

        assertCode(() -> service.login(EMAIL, PASSWORD, CLIENT), ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void deactivatedAccountGets403AfterCorrectPassword() {
        user.deactivate();
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(true);

        assertCode(() -> service.login(EMAIL, PASSWORD, CLIENT), ErrorCode.AUTH_ACCOUNT_DISABLED);
        verify(refreshTokens, never()).issueForNewLogin(any(), any());
    }

    @Test
    void outdatedHashIsUpgradedOnSuccessfulLogin() {
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(true);
        when(encoder.upgradeEncoding(REAL_HASH)).thenReturn(true);
        when(encoder.encode(PASSWORD)).thenReturn("{argon2}upgraded");

        service.login(EMAIL, PASSWORD, CLIENT);

        verify(users).upgradePasswordHash(user.getId(), "{argon2}upgraded", NOW);
    }

    @Test
    void currentHashIsNotRewritten() {
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(true);

        service.login(EMAIL, PASSWORD, CLIENT);

        verify(users, never()).upgradePasswordHash(any(), anyString(), any());
    }

    @Test
    void refreshPairsTheRotatedTokenWithANewAccessToken() {
        IssuedRefreshToken rotated = new IssuedRefreshToken("rotated", NOW.plus(Duration.ofDays(7)));
        when(refreshTokens.rotate("old", CLIENT)).thenReturn(new RefreshTokenService.Rotation(user.getId(), rotated));

        LoginResult result = service.refresh("old", CLIENT);

        assertThat(result.refreshToken()).isSameAs(rotated);
        assertThat(result.accessToken().value()).isEqualTo("jwt");
    }

    @Test
    void logoutAllRevokesWithTheRightReason() {
        service.logoutAll(user.getId());

        verify(refreshTokens).revokeAll(user.getId(), com.ecommerce.backend.auth.entity.RevokeReason.LOGOUT_ALL);
    }

    @Test
    void failedLoginsArePaddedToTheFloorButSuccessesAreNot() {
        TransactionOperations noTx = TransactionOperations.withoutTransaction();
        AuthProperties props = TestAuthProperties.withFailedLoginFloor(Duration.ofMillis(150));
        AuthenticationService padded = new AuthenticationService(users, refreshTokens, accessTokens, encoder,
                new FailedLoginRecorder(users, events, noTx, props), noTx, props, Clock.fixed(NOW, ZoneOffset.UTC));
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(true);

        long failureStart = System.nanoTime();
        assertCode(() -> padded.login("nobody@example.com", PASSWORD, CLIENT), ErrorCode.AUTH_INVALID_CREDENTIALS);
        long failureMillis = (System.nanoTime() - failureStart) / 1_000_000;

        long successStart = System.nanoTime();
        padded.login(EMAIL, PASSWORD, CLIENT);
        long successMillis = (System.nanoTime() - successStart) / 1_000_000;

        assertThat(failureMillis).isGreaterThanOrEqualTo(145);
        assertThat(successMillis).isLessThan(145);
    }

    @Test
    void notVerifiedIsNotPaddedBecauseThePasswordWasCorrect() {
        TransactionOperations noTx = TransactionOperations.withoutTransaction();
        AuthProperties props = TestAuthProperties.withFailedLoginFloor(Duration.ofSeconds(5));
        AuthenticationService padded = new AuthenticationService(users, refreshTokens, accessTokens, encoder,
                new FailedLoginRecorder(users, events, noTx, props), noTx, props, Clock.fixed(NOW, ZoneOffset.UTC));
        User pending = User.register(EMAIL, REAL_HASH, NOW);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(pending));
        when(encoder.matches(PASSWORD, REAL_HASH)).thenReturn(true);

        long start = System.nanoTime();
        assertCode(() -> padded.login(EMAIL, PASSWORD, CLIENT), ErrorCode.AUTH_EMAIL_NOT_VERIFIED);

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
