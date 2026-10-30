package com.ecommerce.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.auth.entity.RefreshToken;
import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.event.RefreshTokenReuseDetected;
import com.ecommerce.backend.auth.repository.RefreshTokenRepository;
import com.ecommerce.backend.auth.security.OpaqueTokens;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String RAW = "q2vQ8m1f0xY3bS9kLw7pT4rN6uC5aE2dH8jK1zR0sVg";
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "Firefox");

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RefreshTokenService service = new RefreshTokenService(repository, new OpaqueTokens(), events,
            TransactionOperations.withoutTransaction(),
            TestAuthProperties.defaults(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private User user;

    @BeforeEach
    void setUp() {
        user = User.register("alice@example.com", "{argon2}h", NOW.minus(Duration.ofDays(10)));
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.verifyEmail(NOW.minus(Duration.ofDays(10)));
        // Simulate Hibernate assigning the ID on persist.
        when(repository.save(any(RefreshToken.class))).thenAnswer(i -> {
            RefreshToken token = i.getArgument(0);
            ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
            return token;
        });
    }

    private RefreshToken stored(Instant issuedAt) {
        RefreshToken token = RefreshToken.issue(user, UUID.randomUUID(), OpaqueTokens.hash(RAW), issuedAt, TTL, null, null);
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        when(repository.findByTokenHashForUpdate(OpaqueTokens.hash(RAW))).thenReturn(Optional.of(token));
        when(repository.findByTokenHash(OpaqueTokens.hash(RAW))).thenReturn(Optional.of(token));
        return token;
    }

    // ------------------------------------------------------------------ issue

    @Test
    void newLoginStartsAFamilyAndStoresOnlyTheHash() {
        IssuedRefreshToken issued = service.issueForNewLogin(user, CLIENT);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(issued.rawValue()).matches(OpaqueTokens.FORMAT_REGEX);
        assertThat(saved.getValue().getTokenHash()).isEqualTo(OpaqueTokens.hash(issued.rawValue()));
        assertThat(saved.getValue().getFamilyId().version()).isEqualTo(7);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(saved.getValue().getUserAgent()).isEqualTo("Firefox");
    }

    @Test
    void everyLoginGetsItsOwnFamily() {
        service.issueForNewLogin(user, CLIENT);
        service.issueForNewLogin(user, CLIENT);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getFamilyId()).isNotEqualTo(saved.getAllValues().get(1).getFamilyId());
    }

    // ------------------------------------------------------------------ rotate

    @Test
    void rotationReplacesTheTokenWithinTheSameFamily() {
        RefreshToken old = stored(NOW.minus(Duration.ofDays(1)));

        RefreshTokenService.Rotation rotation = service.rotate(RAW, CLIENT);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        RefreshToken successor = saved.getValue();
        assertThat(rotation.userId()).isEqualTo(user.getId());
        assertThat(rotation.refreshToken().rawValue()).isNotEqualTo(RAW);
        assertThat(successor.getFamilyId()).isEqualTo(old.getFamilyId());
        assertThat(successor.getExpiresAt()).isEqualTo(NOW.plus(TTL)); // sliding expiry
        assertThat(old.getRevokeReason()).isEqualTo(RevokeReason.ROTATED);
        assertThat(old.getReplacedById()).isEqualTo(successor.getId());
    }

    @Test
    void reusingARotatedTokenRevokesTheFamilyAndAlertsTheOwner() {
        RefreshToken old = stored(NOW.minus(Duration.ofDays(1)));
        old.markRotated(UUID.randomUUID(), NOW.minusSeconds(60));
        when(repository.revokeAllActiveByFamilyId(old.getFamilyId(), RevokeReason.REUSE_DETECTED, NOW)).thenReturn(1);

        assertInvalid(() -> service.rotate(RAW, CLIENT));

        verify(repository).revokeAllActiveByFamilyId(old.getFamilyId(), RevokeReason.REUSE_DETECTED, NOW);
        verify(events).publishEvent(new RefreshTokenReuseDetected("alice@example.com", old.getFamilyId()));
        verify(repository, never()).save(any());
    }

    @Test
    void replayAgainstAnAlreadyDeadFamilyDoesNotSpamTheOwner() {
        RefreshToken old = stored(NOW.minus(Duration.ofDays(1)));
        old.markRotated(UUID.randomUUID(), NOW.minusSeconds(60));
        when(repository.revokeAllActiveByFamilyId(any(), any(), any())).thenReturn(0);

        assertInvalid(() -> service.rotate(RAW, CLIENT));

        verifyNoInteractions(events);
    }

    @Test
    void loggedOutTokenIsRejectedWithoutReuseAlert() {
        RefreshToken old = stored(NOW.minus(Duration.ofDays(1)));
        old.revoke(RevokeReason.LOGOUT, NOW.minusSeconds(60));

        assertInvalid(() -> service.rotate(RAW, CLIENT));

        verifyNoInteractions(events);
    }

    @Test
    void expiredTokenIsRejected() {
        stored(NOW.minus(TTL).minusSeconds(1));

        assertInvalid(() -> service.rotate(RAW, CLIENT));
        verify(repository, never()).save(any());
    }

    @Test
    void unknownTokenIsRejected() {
        when(repository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());

        assertInvalid(() -> service.rotate(RAW, CLIENT));
    }

    @Test
    void malformedOrMissingTokenIsRejectedWithoutTouchingTheDatabase() {
        assertInvalid(() -> service.rotate(null, CLIENT));
        assertInvalid(() -> service.rotate("not a token", CLIENT));

        verifyNoInteractions(repository);
    }

    @Test
    void deactivatedUserCannotRefreshAndTheTokenIsKilled() {
        RefreshToken token = stored(NOW.minus(Duration.ofDays(1)));
        user.deactivate();

        assertInvalid(() -> service.rotate(RAW, CLIENT));

        assertThat(token.getRevokeReason()).isEqualTo(RevokeReason.ACCOUNT_DEACTIVATED);
        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------------ revoke

    @Test
    void logoutRevokesTheToken() {
        RefreshToken token = stored(NOW.minus(Duration.ofDays(1)));

        service.revoke(RAW);

        assertThat(token.getRevokeReason()).isEqualTo(RevokeReason.LOGOUT);
    }

    @Test
    void logoutIsSilentForUnknownMalformedOrMissingTokens() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatCode(() -> {
            service.revoke(RAW);
            service.revoke("garbage");
            service.revoke(null);
        }).doesNotThrowAnyException();
    }

    @Test
    void logoutDoesNotOverwriteAnEarlierRevocationReason() {
        RefreshToken token = stored(NOW.minus(Duration.ofDays(1)));
        token.revoke(RevokeReason.REUSE_DETECTED, NOW.minusSeconds(5));

        service.revoke(RAW);

        assertThat(token.getRevokeReason()).isEqualTo(RevokeReason.REUSE_DETECTED);
    }

    @Test
    void revokeAllDelegatesToTheBulkUpdate() {
        when(repository.revokeAllActiveByUserId(user.getId(), RevokeReason.LOGOUT_ALL, NOW)).thenReturn(3);

        assertThat(service.revokeAll(user.getId(), RevokeReason.LOGOUT_ALL)).isEqualTo(3);
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_REFRESH_TOKEN));
    }
}
