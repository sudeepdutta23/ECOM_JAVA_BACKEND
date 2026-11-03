package com.ecommerce.backend.org.service;

import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.SellerInfo;
import com.ecommerce.backend.org.entity.Organization;
import com.ecommerce.backend.org.repository.OrganizationMemberRepository;
import com.ecommerce.backend.org.repository.OrganizationRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public facts about sellers, for the storefront: only ACTIVE sellers exist here. Pending,
 * suspended and closed sellers are simply absent, so a storefront cannot show or link them.
 */
@Service
public class SellerDirectory {

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;

    public SellerDirectory(OrganizationRepository organizations, OrganizationMemberRepository members) {
        this.organizations = organizations;
        this.members = members;
    }

    /** Everyone who works for the seller, whatever their role: who to tell about its orders. */
    @Transactional(readOnly = true)
    public List<UUID> memberIds(UUID organizationId) {
        return members.findUserIds(organizationId);
    }

    /** One query for a whole page of products (no N+1). IDs of sellers that are not active are absent. */
    @Transactional(readOnly = true)
    public Map<UUID, SellerInfo> activeSellers(Collection<UUID> organizationIds) {
        if (organizationIds.isEmpty()) {
            return Map.of();
        }
        return organizations.findAllById(organizationIds).stream()
                .filter(Organization::isActive)
                .map(SellerDirectory::info)
                .collect(Collectors.toUnmodifiableMap(SellerInfo::id, Function.identity()));
    }

    /** @param slug any case; slugs are stored lower-case */
    @Transactional(readOnly = true)
    public Optional<SellerInfo> findActiveBySlug(String slug) {
        return organizations.findBySlug(Organization.normalizeSlug(slug)).filter(Organization::isActive)
                .map(SellerDirectory::info);
    }

    /**
     * Checkout: whether this seller may sell right now, with a FOR SHARE lock on it until the
     * caller's transaction ends. A suspension then waits for the checkout, and its event takes the
     * products off sale afterwards; a checkout that starts after it sees the new status. Only ACTIVE
     * sellers sell; pending ones prepare their catalogue but take no orders.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockIfSelling(UUID organizationId) {
        return organizations.lockStatus(organizationId).map(OrganizationStatus.ACTIVE.name()::equals).orElse(false);
    }

    private static SellerInfo info(Organization organization) {
        return new SellerInfo(organization.getId(), organization.getSlug(), organization.getName());
    }
}
