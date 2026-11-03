package com.ecommerce.backend.org.service;

import com.ecommerce.backend.common.audit.Audited;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.org.dto.MemberResponse;
import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.entity.OrganizationMember;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.repository.OrganizationRepository;
import com.ecommerce.backend.org.service.OrganizationGuard.Intent;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who works for an organisation, and in which role.
 *
 * <p>Rules for changing or removing a member, checked in this order:
 * <ol>
 *   <li>not your own membership (403 {@code ORG_SELF_ACTION_DENIED}); to step down, leave;</li>
 *   <li>you are a member with {@code MEMBER_MANAGE} (404 for outsiders, 403 otherwise);</li>
 *   <li>the organisation is pending or active (409 {@code ORG_INVALID_STATUS});</li>
 *   <li>the rank rule, for the member's current AND new role (403 {@code ORG_RANK_DENIED});</li>
 *   <li>an organisation that is not closed keeps at least one owner whose account is active (409
 *       {@code ORG_LAST_OWNER}).</li>
 * </ol>
 *
 * <p>Every change takes the organisation row lock first ({@code findByIdForUpdate}), so concurrent
 * changes to the owners of one organisation are serialised and rule 5 cannot be raced.
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

    /** Longest-serving first; the user ID makes the order total. */
    static final Sort BY_SENIORITY = Sort.by("joinedAt", "userId");

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final OrganizationGuard guard;
    private final UserDirectory users;

    public MembershipService(OrganizationRepository organizations, OrganizationMemberRepository members,
                             OrganizationGuard guard, UserDirectory users) {
        this.organizations = organizations;
        this.members = members;
        this.guard = guard;
        this.users = users;
    }

    /** Every member can see colleagues. One query for the page, one for their emails. */
    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> list(UUID callerId, UUID organizationId, int page, int size) {
        guard.require(organizationId, callerId, OrgPermission.ORGANIZATION_READ, Intent.READ);
        Page<OrganizationMember> result = members.findByOrganizationId(organizationId,
                PageRequest.of(page, size, BY_SENIORITY));
        Map<UUID, String> emails = users.emailsOf(result.map(OrganizationMember::getUserId).toList());
        return PageResponse.from(result, member -> MemberResponse.from(member, emails.get(member.getUserId())));
    }

    @Transactional
    @Audited(action = "org.member-role", targetType = "user", targetId = "#userId", organizationId = "#organizationId", detail = "'role=' + #newRole")
    public MemberResponse changeRole(UUID callerId, UUID organizationId, UUID userId, OrgRole newRole) {
        requireNotSelf(callerId, userId);
        lock(organizationId);
        // After the lock: the role and status read here cannot change until this transaction ends.
        OrgMembership caller = guard.require(organizationId, callerId, OrgPermission.MEMBER_MANAGE, Intent.WRITE);
        OrganizationMember member = requireMember(organizationId, userId);
        OrganizationGuard.requireRank(caller.role(), member.getRole());
        OrganizationGuard.requireRank(caller.role(), newRole);
        if (member.getRole() == OrgRole.OWNER && newRole != OrgRole.OWNER) {
            requireAnotherActiveOwner(organizationId, userId);
        }

        OrgRole previous = member.getRole();
        member.changeRole(newRole);
        log.info("Member role changed: organizationId={}, userId={}, from={}, to={}, by={}",
                organizationId, userId, previous, newRole, callerId);
        return MemberResponse.from(member, users.emailsOf(List.of(userId)).get(userId));
    }

    @Transactional
    @Audited(action = "org.member-remove", targetType = "user", targetId = "#userId", organizationId = "#organizationId")
    public void remove(UUID callerId, UUID organizationId, UUID userId) {
        requireNotSelf(callerId, userId);
        lock(organizationId);
        // After the lock: the role and status read here cannot change until this transaction ends.
        OrgMembership caller = guard.require(organizationId, callerId, OrgPermission.MEMBER_MANAGE, Intent.WRITE);
        OrganizationMember member = requireMember(organizationId, userId);
        OrganizationGuard.requireRank(caller.role(), member.getRole());
        if (member.getRole() == OrgRole.OWNER) {
            requireAnotherActiveOwner(organizationId, userId);
        }

        members.delete(member);
        log.info("Member removed: organizationId={}, userId={}, role={}, by={}",
                organizationId, userId, member.getRole(), callerId);
    }

    /**
     * Steps down from an organisation. Allowed in any status, a suspended one included: nobody is
     * kept against their will. The last owner of an organisation that is not closed must first
     * hand ownership to someone else, or close it.
     */
    @Transactional
    public void leave(UUID callerId, UUID organizationId) {
        Organization organization = lock(organizationId);
        OrgMembership member = guard.require(organizationId, callerId, OrgPermission.ORGANIZATION_READ, Intent.READ);
        if (member.role() == OrgRole.OWNER && !organization.isClosed()) {
            requireAnotherActiveOwner(organizationId, callerId);
        }
        members.deleteById(new OrganizationMember.Key(organizationId, callerId));
        log.info("Member left: organizationId={}, userId={}, role={}", organizationId, callerId, member.role());
    }

    // ------------------------------------------------------------------ guards

    /** Outsiders get the same 404 here as from {@link OrganizationGuard#require}. */
    private Organization lock(UUID organizationId) {
        return organizations.findByIdForUpdate(organizationId).orElseThrow(() -> new ResourceNotFoundException("Organization"));
    }

    private OrganizationMember requireMember(UUID organizationId, UUID userId) {
        return members.findById(new OrganizationMember.Key(organizationId, userId))
                .orElseThrow(() -> new ResourceNotFoundException("Member"));
    }

    /**
     * Another owner must remain who can actually sign in. Counting memberships alone would let the
     * last active owner leave a shop whose other owners are deactivated or suspended (Phase 4 review).
     */
    private void requireAnotherActiveOwner(UUID organizationId, UUID departingUserId) {
        List<UUID> others = members.findOwnerIds(organizationId).stream()
                .filter(ownerId -> !ownerId.equals(departingUserId))
                .toList();
        if (users.countActive(others) == 0) {
            throw new BusinessException(ErrorCode.ORG_LAST_OWNER, "This is the last owner who can sign in. Make "
                    + "someone else an owner first, or close the organisation.");
        }
    }

    private static void requireNotSelf(UUID callerId, UUID userId) {
        if (callerId.equals(userId)) {
            throw new BusinessException(ErrorCode.ORG_SELF_ACTION_DENIED,
                    "You cannot change or remove your own membership. To step down, leave the organisation.");
        }
    }
}
