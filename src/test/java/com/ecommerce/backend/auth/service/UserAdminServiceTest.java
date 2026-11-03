package com.ecommerce.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.auth.dto.AdminUserDetail;
import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.event.AccountErased;
import com.ecommerce.backend.auth.repository.AccountTokenRepository;
import com.ecommerce.backend.auth.repository.RefreshTokenRepository;
import com.ecommerce.backend.auth.repository.UserRepository;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.order.service.OrderEraser;
import com.ecommerce.backend.org.service.MemberDeparture;
import com.ecommerce.backend.profile.service.ProfileEraser;
import com.ecommerce.backend.rbac.service.AuthorizationService;
import com.ecommerce.backend.rbac.service.RoleAssignmentService;
import com.ecommerce.backend.rbac.service.UserAuthorities;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class UserAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final UUID ADMIN = UUID.randomUUID();

    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RefreshTokenService sessions = mock(RefreshTokenService.class);
    private final RoleAssignmentService roleAssignments = mock(RoleAssignmentService.class);
    private final AccountTokenRepository accountTokens = mock(AccountTokenRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final MemberDeparture departure = mock(MemberDeparture.class);
    private final ProfileEraser profiles = mock(ProfileEraser.class);
    private final AccountEraser eraser = new AccountEraser(refreshTokens, accountTokens, roleAssignments, departure,
            profiles, mock(OrderEraser.class), events);
    private final UserAdminService service = new UserAdminService(users, refreshTokens, authorization, sessions,
            roleAssignments, eraser, Clock.fixed(NOW, ZoneOffset.UTC));

    private User target;

    @BeforeEach
    void setUp() {
        target = User.register("bob@example.com", "{argon2}h", NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(target, "id", UUID.randomUUID());
        target.verifyEmail(NOW.minusSeconds(3000));
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(authorization.authoritiesOf(any())).thenReturn(UserAuthorities.none());
    }

    // ------------------------------------------------------------------ suspend

    @Test
    void suspendRecordsTheReasonAndEndsSessionsAfterTheLastAdminCheck() {
        AdminUserDetail detail = service.suspend(ADMIN, target.getId(), "Chargeback fraud");

        assertThat(target.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(target.getStatusChangedBy()).isEqualTo(ADMIN);
        assertThat(detail.statusReason()).isEqualTo("Chargeback fraud");
        InOrder order = inOrder(authorization, roleAssignments, sessions);
        order.verify(authorization).requireCanManage(ADMIN, target.getId());
        order.verify(roleAssignments).requireCanLeave(target.getId());
        order.verify(sessions).revokeAll(target.getId(), RevokeReason.ACCOUNT_SUSPENDED);
    }

    @Test
    void onlyActiveAccountsCanBeSuspended() {
        target.deactivate(NOW);

        assertCode(() -> service.suspend(ADMIN, target.getId(), "Abuse"), ErrorCode.USER_INVALID_STATUS);
        verify(sessions, never()).revokeAll(any(), any());
    }

    @Test
    void theLastAdminCannotBeSuspendedAndNothingChanges() {
        doThrow(new BusinessException(ErrorCode.RBAC_LAST_ADMIN, "last")).when(roleAssignments).requireCanLeave(target.getId());

        assertCode(() -> service.suspend(ADMIN, target.getId(), "Abuse"), ErrorCode.RBAC_LAST_ADMIN);
        assertThat(target.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(sessions, never()).revokeAll(any(), any());
    }

    // ------------------------------------------------------------------ shared guards

    @Test
    void noActionWorksOnYourOwnAccount() {
        UUID self = target.getId();

        assertCode(() -> service.suspend(self, self, "x"), ErrorCode.USER_SELF_ACTION_DENIED);
        assertCode(() -> service.reactivate(self, self), ErrorCode.USER_SELF_ACTION_DENIED);
        assertCode(() -> service.unlock(self, self), ErrorCode.USER_SELF_ACTION_DENIED);
        assertCode(() -> service.signOut(self, self), ErrorCode.USER_SELF_ACTION_DENIED);
        assertCode(() -> service.erase(self, self), ErrorCode.USER_SELF_ACTION_DENIED);
        verifyNoInteractions(sessions, roleAssignments, accountTokens);
    }

    @Test
    void aStrongerAccountCannotBeTouched() {
        doThrow(new BusinessException(ErrorCode.RBAC_ESCALATION_DENIED, "no"))
                .when(authorization).requireCanManage(ADMIN, target.getId());

        assertCode(() -> service.suspend(ADMIN, target.getId(), "x"), ErrorCode.RBAC_ESCALATION_DENIED);
        assertCode(() -> service.signOut(ADMIN, target.getId()), ErrorCode.RBAC_ESCALATION_DENIED);
        assertCode(() -> service.unlock(ADMIN, target.getId()), ErrorCode.RBAC_ESCALATION_DENIED);
        assertCode(() -> service.erase(ADMIN, target.getId()), ErrorCode.RBAC_ESCALATION_DENIED);
        verify(users, never()).clearLockout(any(), any());
        verifyNoInteractions(sessions, accountTokens);
        assertThat(target.isErased()).isFalse();
    }

    @Test
    void unknownAccountIs404() {
        UUID ghost = UUID.randomUUID();
        when(users.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlock(ADMIN, ghost)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ reactivate, unlock, sign out

    @Test
    void reactivateReopensASuspendedAccountAndClearsTheReason() {
        target.suspend("Abuse", ADMIN, NOW);

        service.reactivate(ADMIN, target.getId());

        assertThat(target.isActive()).isTrue();
        assertThat(target.getStatusReason()).isNull();
    }

    @Test
    void activeAccountsCannotBeReactivated() {
        assertCode(() -> service.reactivate(ADMIN, target.getId()), ErrorCode.USER_INVALID_STATUS);
    }

    @Test
    void unlockClearsTheLockoutAtomically() {
        service.unlock(ADMIN, target.getId());

        verify(users).clearLockout(target.getId(), NOW);
    }

    @Test
    void signOutEndsEverySessionWithItsOwnReason() {
        service.signOut(ADMIN, target.getId());

        verify(sessions).revokeAll(target.getId(), RevokeReason.ADMIN_SIGN_OUT);
        assertThat(target.isActive()).isTrue();
    }

    // ------------------------------------------------------------------ erase

    @Test
    void eraseWorksFromAnyStatusAndChecksBeforeDeleting() {
        target.suspend("Chargeback fraud", ADMIN, NOW.minusSeconds(60));

        AdminUserDetail detail = service.erase(ADMIN, target.getId());

        assertThat(detail.status()).isEqualTo(UserStatus.ERASED);
        assertThat(detail.email()).isEqualTo("erased-" + target.getId() + "@erased.invalid");
        assertThat(detail.statusReason()).isNull(); // the internal note may itself contain personal data
        assertThat(detail.statusChangedBy()).isEqualTo(ADMIN);
        InOrder order = inOrder(authorization, roleAssignments, refreshTokens, accountTokens);
        order.verify(authorization).requireCanManage(ADMIN, target.getId());
        order.verify(roleAssignments).requireCanLeave(target.getId());
        order.verify(refreshTokens).deleteAllByUserId(target.getId());
        order.verify(accountTokens).deleteAllByUserId(target.getId());
        order.verify(roleAssignments).removeAllRoles(target.getId());
        verify(events).publishEvent(new AccountErased("bob@example.com")); // the old address, for the confirmation
    }

    @Test
    void anErasedAccountCannotBeErasedAgain() {
        target.erase(ADMIN, NOW);

        assertCode(() -> service.erase(ADMIN, target.getId()), ErrorCode.USER_INVALID_STATUS);
        verifyNoInteractions(accountTokens, events);
    }

    @Test
    void theLastAdminCannotBeErasedAndNothingChanges() {
        doThrow(new BusinessException(ErrorCode.RBAC_LAST_ADMIN, "last")).when(roleAssignments).requireCanLeave(target.getId());

        assertCode(() -> service.erase(ADMIN, target.getId()), ErrorCode.RBAC_LAST_ADMIN);
        assertThat(target.getEmail()).isEqualTo("bob@example.com");
        verify(refreshTokens, never()).deleteAllByUserId(any());
        verify(roleAssignments, never()).removeAllRoles(any());
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
