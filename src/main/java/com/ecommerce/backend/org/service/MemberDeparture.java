package com.ecommerce.backend.org.service;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.repository.OrganizationInvitationRepository;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.repository.OrganizationRepository;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the auth module asks before, and does while, an account goes away (ORGANIZATIONS.md, rules
 * 6 to 8). Both methods join the caller's transaction, like rbac's {@code requireCanLeave}: the
 * checks and the account change commit together or not at all.
 *
 * <p>Not called for an administrator's suspension: that is reversible, and stopping fraud must not
 * wait for an ownership hand-over.
 */
@Service
public class MemberDeparture {

    private static final Logger log = LoggerFactory.getLogger(MemberDeparture.class);

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final OrganizationInvitationRepository invitations;
    private final UserDirectory users;

    public MemberDeparture(OrganizationRepository organizations, OrganizationMemberRepository members,
                           OrganizationInvitationRepository invitations, UserDirectory users) {
        this.organizations = organizations;
        this.members = members;
        this.invitations = invitations;
        this.users = users;
    }

    /** What erasure removed, for the log line. */
    public record Removed(int memberships, int invitations) {
    }

    /**
     * Refuses (409 {@code ORG_LAST_OWNER}) if no other owner who can sign in remains in an organisation
     * that is not closed: deactivating or erasing the account would leave a seller nobody can manage. Locks those
     * organisations until the caller's transaction ends, so a co-owner cannot leave meanwhile.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNotSoleOwner(UUID userId) {
        List<String> soleOwned = organizations.lockOpenOwnedBy(userId).stream()
                .filter(organization -> !hasAnotherActiveOwner(organization, userId))
                .map(Organization::getSlug)
                .toList();
        if (!soleOwned.isEmpty()) {
            throw new BusinessException(ErrorCode.ORG_LAST_OWNER, "You are the only active owner of "
                    + String.join(", ", soleOwned) + ". Make someone else an owner, or close "
                    + (soleOwned.size() == 1 ? "it" : "them") + ", first.");
        }
    }

    /** Owners whose accounts are deactivated or suspended cannot manage the shop, so they do not count. */
    private boolean hasAnotherActiveOwner(Organization organization, UUID userId) {
        List<UUID> others = members.findOwnerIds(organization.getId()).stream()
                .filter(ownerId -> !ownerId.equals(userId))
                .toList();
        return users.countActive(others) > 0;
    }

    /**
     * Erasure: removes every membership and every invitation ever sent to the address. What the
     * organisations themselves keep ({@code created_by}, {@code added_by}, {@code invited_by}) is the
     * account's ID, which after erasure points to an anonymous row.
     *
     * @param email the address the account had before erasure
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Removed forget(UUID userId, String email) {
        Removed removed = new Removed(members.deleteAllByUserId(userId), invitations.deleteAllByEmail(email));
        log.info("Organisation data of an erased account removed: userId={}, {}", userId, removed);
        return removed;
    }
}
