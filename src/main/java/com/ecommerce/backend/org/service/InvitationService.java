package com.ecommerce.backend.org.service;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.common.security.OpaqueTokens;
import com.ecommerce.backend.org.dto.InvitationResponse;
import com.ecommerce.backend.org.dto.OrganizationResponse;
import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.entity.OrganizationInvitation;
import com.ecommerce.backend.org.entity.OrganizationMember;
import com.ecommerce.backend.org.event.InvitationCreated;
import com.ecommerce.backend.org.repository.OrganizationInvitationRepository;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.repository.OrganizationRepository;
import com.ecommerce.backend.org.service.OrganizationGuard.Intent;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Joining an organisation, always with the invitee's consent: a member invites an email address,
 * and only the signed-in account with that address can accept the emailed link.
 *
 * <p>Inviting does not reveal whether the address has an account: the answer and the email are
 * the same either way. The invitee may register after receiving it.
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final OrganizationInvitationRepository invitations;
    private final OrganizationGuard guard;
    private final UserDirectory users;
    private final OpaqueTokens opaqueTokens;
    private final ApplicationEventPublisher events;
    private final OrgProperties properties;
    private final Clock clock;

    public InvitationService(OrganizationRepository organizations, OrganizationMemberRepository members,
                             OrganizationInvitationRepository invitations, OrganizationGuard guard,
                             UserDirectory users, OpaqueTokens opaqueTokens, ApplicationEventPublisher events,
                             OrgProperties properties, Clock clock) {
        this.organizations = organizations;
        this.members = members;
        this.invitations = invitations;
        this.guard = guard;
        this.users = users;
        this.opaqueTokens = opaqueTokens;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Invites an address with a role below the caller's own (owners may invite owners). Inviting the
     * same address again replaces the open invitation, so the newest link is the only one that works.
     */
    @Transactional
    public InvitationResponse invite(UUID callerId, UUID organizationId, String rawEmail, OrgRole role) {
        OrgMembership caller = guard.require(organizationId, callerId, OrgPermission.MEMBER_MANAGE, Intent.WRITE);
        OrganizationGuard.requireRank(caller.role(), role);
        Organization organization = load(organizationId);
        String email = OrganizationInvitation.normalizeEmail(rawEmail);
        boolean alreadyMember = users.findActiveUserId(email)
                .map(userId -> members.existsById(new OrganizationMember.Key(organizationId, userId)))
                .orElse(false);
        if (alreadyMember) {
            throw new BusinessException(ErrorCode.ORG_ALREADY_MEMBER, "This person is already a member.");
        }

        Instant now = Instant.now(clock);
        invitations.findOpen(organizationId, email).ifPresentOrElse(previous -> {
            previous.revoke(now);
            invitations.flush(); // frees uk_organization_invitations_open before the new row is inserted
        }, () -> requireBelowPendingLimit(organizationId, now));

        OpaqueTokens.Generated token = opaqueTokens.generate();
        OrganizationInvitation invitation = invitations.save(OrganizationInvitation.issue(organizationId, email, role,
                token.hash(), callerId, now, properties.invitationTtl()));
        events.publishEvent(new InvitationCreated(email, organization.getName(), role, token.raw(), invitation.getExpiresAt()));
        log.info("Invitation sent: organizationId={}, invitationId={}, role={}, by={}",
                organizationId, invitation.getId(), role, callerId);
        return InvitationResponse.from(invitation);
    }

    @Transactional(readOnly = true)
    public PageResponse<InvitationResponse> pending(UUID callerId, UUID organizationId, int page, int size) {
        guard.require(organizationId, callerId, OrgPermission.MEMBER_MANAGE, Intent.READ);
        return PageResponse.from(invitations.findPending(organizationId, Instant.now(clock), PageRequest.of(page, size)),
                InvitationResponse::from);
    }

    /** Withdraws an invitation for a role you could have invited. Idempotent. */
    @Transactional
    public void revoke(UUID callerId, UUID organizationId, UUID invitationId) {
        OrgMembership caller = guard.require(organizationId, callerId, OrgPermission.MEMBER_MANAGE, Intent.WRITE);
        OrganizationInvitation invitation = invitations.findByIdAndOrganizationId(invitationId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation"));
        OrganizationGuard.requireRank(caller.role(), invitation.getRole());
        invitation.revoke(Instant.now(clock));
        log.info("Invitation revoked: organizationId={}, invitationId={}, by={}", organizationId, invitationId, callerId);
    }

    /**
     * The invitee accepts. Every failure about the link itself (unknown, used, revoked, expired, or
     * meant for another address) is the same 400 {@code ORG_INVALID_INVITATION}: the answer must not
     * help someone holding a forwarded or guessed link.
     */
    @Transactional
    public OrganizationResponse accept(UUID callerId, String rawToken) {
        Instant now = Instant.now(clock);
        OrganizationInvitation invitation = invitations.findByTokenHashForUpdate(OpaqueTokens.hash(rawToken))
                .orElseThrow(InvitationService::invalidInvitation);
        if (!invitation.isOpenAt(now)) {
            log.info("Invitation rejected: reason=NOT_OPEN, invitationId={}", invitation.getId());
            throw invalidInvitation();
        }
        if (!users.isActive(callerId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "This account is no longer available.");
        }
        if (!invitation.getEmail().equals(users.emailsOf(List.of(callerId)).get(callerId))) {
            log.info("Invitation rejected: reason=OTHER_ACCOUNT, invitationId={}, userId={}", invitation.getId(), callerId);
            throw invalidInvitation();
        }
        UUID organizationId = invitation.getOrganizationId();
        Organization organization = load(organizationId);
        OrganizationGuard.requireWritable(organization.getStatus());
        if (members.existsById(new OrganizationMember.Key(organizationId, callerId))) {
            throw new BusinessException(ErrorCode.ORG_ALREADY_MEMBER, "You are already a member of this organisation.");
        }

        members.save(OrganizationMember.join(organizationId, callerId, invitation.getRole(), now, invitation.getInvitedBy()));
        invitation.accept(now);
        log.info("Invitation accepted: organizationId={}, invitationId={}, userId={}, role={}",
                organizationId, invitation.getId(), callerId, invitation.getRole());
        return OrganizationResponse.from(organization, invitation.getRole());
    }

    private void requireBelowPendingLimit(UUID organizationId, Instant now) {
        if (invitations.countPending(organizationId, now) >= properties.maxPendingInvitations()) {
            throw new BusinessException(ErrorCode.ORG_INVITATION_LIMIT, "This organisation has "
                    + properties.maxPendingInvitations() + " pending invitations. Revoke some or wait for answers.");
        }
    }

    private Organization load(UUID organizationId) {
        return organizations.findById(organizationId).orElseThrow(() -> new ResourceNotFoundException("Organization"));
    }

    private static BusinessException invalidInvitation() {
        return new BusinessException(ErrorCode.ORG_INVALID_INVITATION,
                "This invitation is invalid, expired, or meant for another email address.");
    }
}
