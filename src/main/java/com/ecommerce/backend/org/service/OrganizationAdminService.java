package com.ecommerce.backend.org.service;

import com.ecommerce.backend.common.audit.Audited;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.OrganizationStatusChanged;
import com.ecommerce.backend.org.dto.AdminOrganizationDetail;
import com.ecommerce.backend.org.dto.AdminOrganizationSummary;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.entity.OrganizationMember;
import com.ecommerce.backend.org.repository.OrganizationInvitationRepository;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.repository.OrganizationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform staff and sellers: review, approve, suspend, reactivate, close (step 4.5). Authorised by
 * platform permissions ({@code org:read}, {@code org:manage}) on the controller, not by membership.
 *
 * <p><b>Conflict of interest:</b> staff cannot act on an organisation they are a member of (403
 * {@code ORG_CONFLICT_OF_INTEREST}). Otherwise an administrator could approve their own shop, or
 * lift its suspension, and the review would mean nothing. Reading is allowed.
 *
 * <p>The suspension reason is an internal note: stored and shown here, never to members, never logged.
 */
@Service
public class OrganizationAdminService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationAdminService.class);

    /** Newest first; UUID v7, so creation order, and unique. */
    static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final OrganizationInvitationRepository invitations;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OrganizationAdminService(OrganizationRepository organizations, OrganizationMemberRepository members,
                                    OrganizationInvitationRepository invitations, ApplicationEventPublisher events,
                                    Clock clock) {
        this.organizations = organizations;
        this.members = members;
        this.invitations = invitations;
        this.events = events;
        this.clock = clock;
    }

    /** The approval queue is {@code status=PENDING_APPROVAL}. Served by {@code idx_organizations_status_id}. */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrganizationSummary> list(OrganizationStatus status, int page, int size) {
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        return PageResponse.from(status == null ? organizations.findAll(request) : organizations.findByStatus(status, request),
                AdminOrganizationSummary::from);
    }

    @Transactional(readOnly = true)
    public AdminOrganizationDetail get(UUID organizationId) {
        return detail(load(organizationId));
    }

    @Transactional
    @Audited(action = "org.approve", targetType = "organization", targetId = "#organizationId", organizationId = "#organizationId")
    public AdminOrganizationDetail approve(UUID callerId, UUID organizationId) {
        Organization organization = manageable(callerId, organizationId);
        requireStatus(organization, OrganizationStatus.PENDING_APPROVAL, "only pending organisations can be approved");
        organization.approve(callerId, Instant.now(clock));
        events.publishEvent(new OrganizationStatusChanged(organizationId, organization.getStatus()));
        log.info("Organisation approved: organizationId={}, by={}", organizationId, callerId);
        return detail(organization);
    }

    /** Members keep read access; every change and every sale (Phase 6) is refused until reactivation. */
    @Transactional
    @Audited(action = "org.suspend", targetType = "organization", targetId = "#organizationId", organizationId = "#organizationId")
    public AdminOrganizationDetail suspend(UUID callerId, UUID organizationId, String reason) {
        Organization organization = manageable(callerId, organizationId);
        requireStatus(organization, OrganizationStatus.ACTIVE, "only active organisations can be suspended");
        organization.suspend(reason, callerId, Instant.now(clock));
        events.publishEvent(new OrganizationStatusChanged(organizationId, organization.getStatus()));
        log.info("Organisation suspended: organizationId={}, by={}", organizationId, callerId); // never the reason
        return detail(organization);
    }

    @Transactional
    @Audited(action = "org.reactivate", targetType = "organization", targetId = "#organizationId", organizationId = "#organizationId")
    public AdminOrganizationDetail reactivate(UUID callerId, UUID organizationId) {
        Organization organization = manageable(callerId, organizationId);
        requireStatus(organization, OrganizationStatus.SUSPENDED, "only suspended organisations can be reactivated");
        organization.reactivate(callerId, Instant.now(clock));
        events.publishEvent(new OrganizationStatusChanged(organizationId, organization.getStatus()));
        log.info("Organisation reactivated: organizationId={}, by={}", organizationId, callerId);
        return detail(organization);
    }

    /** Closes for good, from any status; for example a seller that never answered the review. */
    @Transactional
    @Audited(action = "org.close", targetType = "organization", targetId = "#organizationId", organizationId = "#organizationId")
    public AdminOrganizationDetail close(UUID callerId, UUID organizationId) {
        Organization organization = manageable(callerId, organizationId);
        int revoked = closeAndRevokeInvitations(organization, callerId, invitations, events, Instant.now(clock));
        log.info("Organisation closed by the platform: organizationId={}, by={}, invitationsRevoked={}",
                organizationId, callerId, revoked);
        return detail(organization);
    }

    /**
     * Shared with the owner's own close: the organisation ends, and so do its open invitation links.
     * Callers hold the organisation row lock, so a membership change cannot interleave. Listeners of
     * {@link OrganizationStatusChanged} (the catalog hides the products) run in the same transaction.
     */
    static int closeAndRevokeInvitations(Organization organization, UUID by,
                                         OrganizationInvitationRepository invitations,
                                         ApplicationEventPublisher events, Instant now) {
        if (organization.isClosed()) {
            throw new BusinessException(ErrorCode.ORG_INVALID_STATUS, "This organisation is already closed.");
        }
        organization.close(by, now);
        events.publishEvent(new OrganizationStatusChanged(organization.getId(), organization.getStatus()));
        return invitations.revokeAllOpen(organization.getId(), now); // flushes the status change first
    }

    // ------------------------------------------------------------------ guards

    /** Locks the row: a status change and a membership change of one organisation never interleave. */
    private Organization manageable(UUID callerId, UUID organizationId) {
        Organization organization = organizations.findByIdForUpdate(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization"));
        if (members.existsById(new OrganizationMember.Key(organizationId, callerId))) {
            throw new BusinessException(ErrorCode.ORG_CONFLICT_OF_INTEREST,
                    "You are a member of this organisation. Another administrator must handle it.");
        }
        return organization;
    }

    private Organization load(UUID organizationId) {
        return organizations.findById(organizationId).orElseThrow(() -> new ResourceNotFoundException("Organization"));
    }

    /** The bulk revoke in {@code close} clears the persistence context, so the counts are read by query. */
    private AdminOrganizationDetail detail(Organization organization) {
        UUID id = organization.getId();
        organizations.flush(); // the response shows the new version and timestamps
        Organization current = organizations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organization"));
        return AdminOrganizationDetail.from(current, members.countByOrganizationId(id),
                members.countByOrganizationIdAndRole(id, OrgRole.OWNER));
    }

    private static void requireStatus(Organization organization, OrganizationStatus expected, String rule) {
        if (organization.getStatus() != expected) {
            throw new BusinessException(ErrorCode.ORG_INVALID_STATUS,
                    "This organisation is " + organization.getStatus() + "; " + rule + ".");
        }
    }
}
