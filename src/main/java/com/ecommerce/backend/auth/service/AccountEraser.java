package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.event.AccountErased;
import com.ecommerce.backend.auth.repository.AccountTokenRepository;
import com.ecommerce.backend.auth.repository.RefreshTokenRepository;
import com.ecommerce.backend.notification.service.NotificationEraser;
import com.ecommerce.backend.order.service.OrderEraser;
import com.ecommerce.backend.org.service.MemberDeparture;
import com.ecommerce.backend.profile.service.ProfileEraser;
import com.ecommerce.backend.rbac.service.RoleAssignmentService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that erases an account, for both the owner's request and an administrator's.
 *
 * <p>Everything happens in the caller's transaction: either the account is fully anonymised, or
 * nothing changed. A half-erased account (email gone, sessions still alive) cannot exist.
 *
 * <p>What remains is the {@code users} row with its ID, timestamps and status, so that rows of later
 * phases (orders, invoices) keep a valid reference. Their own personal data is their module's job.
 */
@Component
class AccountEraser {

    private final RefreshTokenRepository refreshTokens;
    private final AccountTokenRepository accountTokens;
    private final RoleAssignmentService roleAssignments;
    private final MemberDeparture organizations;
    private final ProfileEraser profiles;
    private final OrderEraser orders;
    private final NotificationEraser notifications;
    private final ApplicationEventPublisher events;

    AccountEraser(RefreshTokenRepository refreshTokens, AccountTokenRepository accountTokens,
                  RoleAssignmentService roleAssignments, MemberDeparture organizations, ProfileEraser profiles,
                  OrderEraser orders, NotificationEraser notifications, ApplicationEventPublisher events) {
        this.refreshTokens = refreshTokens;
        this.accountTokens = accountTokens;
        this.roleAssignments = roleAssignments;
        this.organizations = organizations;
        this.profiles = profiles;
        this.orders = orders;
        this.notifications = notifications;
        this.events = events;
    }

    /** What was removed, for the log line. */
    record Erasure(int sessionTokens, int emailLinks, int roles, MemberDeparture.Removed organizationData,
                   ProfileEraser.Removed profileData, int cartLines, int notifications) {
    }

    /**
     * @param user a managed entity, already checked by the caller (ownership or escalation rule)
     * @param by   the owner's own ID, or the administrator's
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Erasure erase(User user, UUID by, Instant now) {
        UUID userId = user.getId();
        roleAssignments.requireCanLeave(userId); // locks the ADMIN row until commit
        organizations.requireNotSoleOwner(userId); // locks the organisations the user owns
        String email = user.getEmail();          // read before the bulk deletes clear the context

        user.erase(by, now);
        // Each bulk delete flushes first, so the anonymised row is written before the context is cleared.
        int sessionTokens = refreshTokens.deleteAllByUserId(userId);
        int emailLinks = accountTokens.deleteAllByUserId(userId);
        int roles = roleAssignments.removeAllRoles(userId);
        MemberDeparture.Removed organizationData = organizations.forget(userId, email);
        ProfileEraser.Removed profileData = profiles.forget(userId); // after the user row: a waiting save then sees ERASED
        int cartLines = orders.forget(userId);
        int inbox = notifications.forget(userId);

        events.publishEvent(new AccountErased(email));
        return new Erasure(sessionTokens, emailLinks, roles, organizationData, profileData, cartLines, inbox);
    }
}
