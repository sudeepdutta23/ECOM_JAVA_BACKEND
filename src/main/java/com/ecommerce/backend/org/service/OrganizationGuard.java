package com.ecommerce.backend.org.service;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one gate in front of everything a seller owns (ADR-012): "may this person do X for this
 * organisation, in its current state?", read from the database on every call.
 *
 * <p><b>For seller modules of later phases</b> (catalogue, stock, orders), every service method
 * that takes an {@code organizationId} starts with:
 * <pre>{@code
 * OrgMembership membership = guard.require(organizationId, userId, OrgPermission.CATALOG_WRITE, Intent.WRITE);
 * Product product = products.findByIdAndOrganizationId(productId, organizationId)   // scoped, never findById
 *         .orElseThrow(() -> new ResourceNotFoundException("Product"));
 * }</pre>
 * {@code TenantIsolationIT} then checks every {@code {orgId}} endpoint automatically.
 *
 * <p>Checks, in this order:
 * <ol>
 *   <li>a member: otherwise 404, exactly like an organisation that does not exist. A 403 would
 *       confirm to an outsider that the ID belongs to a seller;</li>
 *   <li>the role grants the permission: otherwise 403 (members know the organisation exists);</li>
 *   <li>for {@link Intent#WRITE}: the organisation is pending or active, otherwise 409
 *       {@code ORG_INVALID_STATUS}. Suspended organisations are read-only; closed ones are history.</li>
 * </ol>
 * One query: the membership row joined to the organisation's status, both by primary key.
 */
@Component
public class OrganizationGuard {

    /** What the caller is about to do; decides which organisation statuses allow it. */
    public enum Intent {
        /** Look at data: allowed in every status, closed included (order history stays readable). */
        READ,
        /** Change data: allowed while pending approval or active. */
        WRITE
    }

    private final OrganizationMemberRepository members;

    public OrganizationGuard(OrganizationMemberRepository members) {
        this.members = members;
    }

    public OrgMembership require(UUID organizationId, UUID userId, OrgPermission permission, Intent intent) {
        OrgMembership membership = members.findMembership(organizationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization"));
        if (!membership.can(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Your role in this organisation (" + membership.role() + ") does not allow this.");
        }
        if (intent == Intent.WRITE) {
            requireWritable(membership.organizationStatus());
        }
        return membership;
    }

    /** The rank rule ({@link OrgRole#canManage}): 403 {@code ORG_RANK_DENIED} for a role at or above yours. */
    static void requireRank(OrgRole callerRole, OrgRole role) {
        if (!callerRole.canManage(role)) {
            throw new BusinessException(ErrorCode.ORG_RANK_DENIED,
                    "As " + callerRole + " you can only act on roles below your own.");
        }
    }

    static void requireWritable(OrganizationStatus status) {
        if (status != OrganizationStatus.PENDING_APPROVAL && status != OrganizationStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ORG_INVALID_STATUS,
                    "This organisation is " + status + " and cannot be changed.");
        }
    }
}
