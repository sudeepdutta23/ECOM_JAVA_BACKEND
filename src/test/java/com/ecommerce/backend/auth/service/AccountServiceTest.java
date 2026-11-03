package com.ecommerce.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.event.AccountDeactivated;
import com.ecommerce.backend.auth.event.AccountErased;
import com.ecommerce.backend.auth.repository.AccountTokenRepository;
import com.ecommerce.backend.auth.repository.RefreshTokenRepository;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.auth.repository.UserSummary;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.org.service.MemberDeparture;
import com.ecommerce.backend.profile.service.ProfileEraser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import com.ecommerce.backend.rbac.service.RoleAssignmentService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

class AccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final String PASSWORD = "Correct-Horse-9-Battery";

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RoleAssignmentService roleAssignments = mock(RoleAssignmentService.class);
    private final RefreshTokenRepository refreshTokenRows = mock(RefreshTokenRepository.class);
    private final AccountTokenRepository accountTokens = mock(AccountTokenRepository.class);
    private final MemberDeparture departure = mock(MemberDeparture.class);
    private final ProfileEraser profiles = mock(ProfileEraser.class);
    private AccountService service;
    private User user;

    @BeforeEach
    void setUp() {
        TransactionOperations noTx = TransactionOperations.withoutTransaction();
        CurrentPasswordCheck check = new CurrentPasswordCheck(encoder,
                new FailedLoginRecorder(users, events, noTx, TestAuthProperties.defaults()));
        AccountEraser eraser = new AccountEraser(refreshTokenRows, accountTokens, roleAssignments, departure,
            profiles, events);
        service = new AccountService(users, check, refreshTokens, events, roleAssignments, eraser, departure, noTx,
                Clock.fixed(NOW, ZoneOffset.UTC));

        user = User.register("alice@example.com", "{argon2}h", NOW);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.verifyEmail(NOW);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(encoder.matches(PASSWORD, "{argon2}h")).thenReturn(true);
    }

    @Test
    void currentUserReturnsTheProjection() {
        UserSummary summary = new UserSummary(user.getId(), "alice@example.com", UserStatus.ACTIVE, NOW, null, NOW);
        when(users.findSummaryById(user.getId())).thenReturn(Optional.of(summary));

        assertThat(service.currentUser(user.getId())).isSameAs(summary);
    }

    @Test
    void currentUserForDeletedAccountIsUnauthorized() {
        UUID ghost = UUID.randomUUID();
        when(users.findSummaryById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.currentUser(ghost)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void deactivateEndsEverySessionAndNotifiesTheOwner() {
        service.deactivate(user.getId(), PASSWORD);

        assertThat(user.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        verify(refreshTokens).revokeAll(user.getId(), RevokeReason.ACCOUNT_DEACTIVATED);
        verify(events).publishEvent(new AccountDeactivated("alice@example.com"));
    }

    @Test
    void deactivateRequiresTheCorrectPassword() {
        when(encoder.matches("wrong", "{argon2}h")).thenReturn(false);

        assertThatThrownBy(() -> service.deactivate(user.getId(), "wrong")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CURRENT_PASSWORD));

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(refreshTokens, never()).revokeAll(any(), any());
    }

    @Test
    void theLastActiveAdministratorCannotDeactivate() {
        doThrow(new BusinessException(ErrorCode.RBAC_LAST_ADMIN, "last"))
                .when(roleAssignments).requireCanLeave(user.getId());

        assertThatThrownBy(() -> service.deactivate(user.getId(), PASSWORD)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RBAC_LAST_ADMIN));

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(refreshTokens, never()).revokeAll(any(), any());
    }

    @Test
    void theOnlyOwnerOfAnOpenOrganizationCannotDeactivateOrErase() {
        doThrow(new BusinessException(ErrorCode.ORG_LAST_OWNER, "only owner"))
                .when(departure).requireNotSoleOwner(user.getId());

        assertThatThrownBy(() -> service.deactivate(user.getId(), PASSWORD)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORG_LAST_OWNER));
        assertThatThrownBy(() -> service.erase(user.getId(), PASSWORD)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORG_LAST_OWNER));

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(refreshTokens, never()).revokeAll(any(), any());
        verify(departure, never()).forget(any(), any());
    }

    @Test
    void erasureForgetsOrganizationDataUnderTheOldAddress() {
        service.erase(user.getId(), PASSWORD);

        verify(departure).forget(user.getId(), "alice@example.com");
    }

    @Test
    void currentUserOfAnErasedAccountIsUnauthorized() {
        when(users.findSummaryById(user.getId())).thenReturn(Optional.of(
                new UserSummary(user.getId(), "erased-x@erased.invalid", UserStatus.ERASED, null, null, NOW)));

        assertThatThrownBy(() -> service.currentUser(user.getId())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void eraseAnonymisesDeletesEverythingAndConfirmsToTheOldAddress() {
        service.erase(user.getId(), PASSWORD);

        assertThat(user.isErased()).isTrue();
        assertThat(user.getEmail()).isEqualTo("erased-" + user.getId() + "@erased.invalid");
        assertThat(user.getStatusChangedBy()).isEqualTo(user.getId());
        verify(roleAssignments).requireCanLeave(user.getId());
        verify(refreshTokenRows).deleteAllByUserId(user.getId());
        verify(accountTokens).deleteAllByUserId(user.getId());
        verify(roleAssignments).removeAllRoles(user.getId());
        verify(events).publishEvent(new AccountErased("alice@example.com"));
    }

    @Test
    void eraseRequiresTheCorrectPassword() {
        when(encoder.matches("wrong", "{argon2}h")).thenReturn(false);

        assertThatThrownBy(() -> service.erase(user.getId(), "wrong")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CURRENT_PASSWORD));

        assertThat(user.isErased()).isFalse();
        verify(refreshTokenRows, never()).deleteAllByUserId(any());
    }

    @Test
    void theLastActiveAdministratorCannotEraseThemselves() {
        doThrow(new BusinessException(ErrorCode.RBAC_LAST_ADMIN, "last"))
                .when(roleAssignments).requireCanLeave(user.getId());

        assertThatThrownBy(() -> service.erase(user.getId(), PASSWORD)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RBAC_LAST_ADMIN));

        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        verify(roleAssignments, never()).removeAllRoles(any());
    }
}
