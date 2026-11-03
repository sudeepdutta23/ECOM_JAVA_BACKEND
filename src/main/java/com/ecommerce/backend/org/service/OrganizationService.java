package com.ecommerce.backend.org.service;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.org.dto.MyOrganizationResponse;
import com.ecommerce.backend.org.dto.OrganizationResponse;
import com.ecommerce.backend.org.MembershipSummary;
import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.entity.OrganizationMember;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seller onboarding and the organisation itself, as seen by its members (Phase 4).
 * Platform administration of organisations comes in step 4.5.
 */
@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

    /** Advisory-lock namespace for organisation creation; any constant no other lock uses. */
    static final int CREATION_LOCK_NAMESPACE = 4_001;

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final OrganizationInvitationRepository invitations;
    private final OrganizationGuard guard;
    private final UserDirectory users;
    private final OrgProperties properties;
    private final Clock clock;

    public OrganizationService(OrganizationRepository organizations, OrganizationMemberRepository members,
                               OrganizationInvitationRepository invitations, OrganizationGuard guard, UserDirectory users, OrgProperties properties, Clock clock) {
        this.organizations = organizations;
        this.members = members;
        this.invitations = invitations;
        this.guard = guard;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates a seller, pending platform approval, with the caller as its first owner, in one
     * transaction: an organisation never exists without an owner.
     *
     * <p>Checks, in this order: the account is active (an access token outlives a suspension by up
     * to 15 minutes); the slug is not reserved (400); the caller owns fewer than the configured
     * number of open organisations (409 {@code ORG_LIMIT_REACHED}); the slug is free (409
     * {@code ORG_SLUG_TAKEN}). Slug taken reveals nothing private: slugs are public storefront handles.
     *
     * @param slug already normalized by the request record
     */
    @Transactional
    public OrganizationResponse create(UUID userId, String slug, String name) {
        if (!users.isActive(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "This account is no longer available.");
        }
        if (Organization.RESERVED_SLUGS.contains(slug)) {
            throw RequestValidationException.of("slug", "is reserved");
        }
        // Held until commit: a parallel request by the same user waits here, then counts this one.
        organizations.lockCreationFor(CREATION_LOCK_NAMESPACE, userId.hashCode());
        long owned = members.countOpenOwnedBy(userId);
        if (owned >= properties.maxOwnedOrganizations()) {
            throw new BusinessException(ErrorCode.ORG_LIMIT_REACHED, "You already own " + owned
                    + " organisations, the maximum. Close one before creating another.");
        }
        if (organizations.existsBySlug(slug)) {
            throw slugTaken(slug);
        }

        Instant now = Instant.now(clock);
        Organization organization;
        try {
            // saveAndFlush: a concurrent creation of the same slug fails HERE, on uk_organizations_slug.
            organization = organizations.saveAndFlush(Organization.create(slug, name, userId, now));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("uk_organizations_slug")) {
                throw slugTaken(slug);
            }
            throw e;
        }
        members.save(OrganizationMember.founder(organization.getId(), userId, now));
        log.info("Organisation created: organizationId={}, slug={}, by={}", organization.getId(), slug, userId);
        return OrganizationResponse.from(organization, OrgRole.OWNER);
    }

    /** The caller's organisations, newest first. Closed ones stay listed: they hold history. */
    @Transactional(readOnly = true)
    public PageResponse<MyOrganizationResponse> mine(UUID userId, int page, int size) {
        return PageResponse.from(members.findMemberships(userId, PageRequest.of(page, size)), MyOrganizationResponse::from);
    }

    /**
     * Every membership of the person, newest organisation first, for their data export. Unpaged:
     * one person belongs to few organisations, and an export must be complete.
     */
    @Transactional(readOnly = true)
    public List<MembershipSummary> allMemberships(UUID userId) {
        return members.findMemberships(userId, Pageable.unpaged()).stream()
                .map(view -> new MembershipSummary(view.organizationId(), view.slug(), view.name(), view.status(),
                        view.role(), view.joinedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationResponse get(UUID userId, UUID organizationId) {
        OrgMembership membership = guard.require(organizationId, userId, OrgPermission.ORGANIZATION_READ, Intent.READ);
        return OrganizationResponse.from(load(organizationId), membership.role());
    }

    /** Renames the organisation. The slug never changes. */
    @Transactional
    public OrganizationResponse update(UUID userId, UUID organizationId, String name, long expectedVersion) {
        OrgMembership membership = guard.require(organizationId, userId, OrgPermission.ORGANIZATION_UPDATE, Intent.WRITE);
        Organization organization = load(organizationId);
        if (organization.getVersion() != expectedVersion) {
            // Fast, explicit check. @Version still protects the window between this read and the commit.
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "This organisation was changed by someone else. Reload it and try again.");
        }
        organization.rename(name);
        organizations.flush(); // bump the version now so the response carries the new value
        log.info("Organisation updated: organizationId={}, by={}", organizationId, userId);
        return OrganizationResponse.from(organization, membership.role());
    }

    /**
     * An owner closes the organisation for good. Its data and slug stay (order history, old links
     * must not lead to someone else); open invitations are revoked. A suspended organisation can
     * only be closed by the platform: closing must not become a way to walk away from a review.
     */
    @Transactional
    public OrganizationResponse close(UUID userId, UUID organizationId) {
        Organization organization = organizations.findByIdForUpdate(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization"));
        OrgMembership membership = guard.require(organizationId, userId, OrgPermission.ORGANIZATION_CLOSE, Intent.WRITE);
        int revoked = OrganizationAdminService.closeAndRevokeInvitations(organization, userId, invitations,
                Instant.now(clock));
        log.info("Organisation closed by an owner: organizationId={}, by={}, invitationsRevoked={}",
                organizationId, userId, revoked);
        return OrganizationResponse.from(load(organizationId), membership.role()); // reloaded: the revoke cleared the context
    }

    private Organization load(UUID organizationId) {
        return organizations.findById(organizationId).orElseThrow(() -> new ResourceNotFoundException("Organization"));
    }

    private static BusinessException slugTaken(String slug) {
        return new BusinessException(ErrorCode.ORG_SLUG_TAKEN, "The slug '" + slug + "' is already taken.");
    }
}
