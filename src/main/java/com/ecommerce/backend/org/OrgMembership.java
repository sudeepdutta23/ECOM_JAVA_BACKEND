package com.ecommerce.backend.org;

import java.util.UUID;

/**
 * The result of {@code OrganizationGuard.require}: who acts, for which organisation, in which role.
 * A plain value, part of the org module's public API, so other modules never see its entities.
 */
public record OrgMembership(UUID organizationId, UUID userId, OrgRole role, OrganizationStatus organizationStatus) {

    public boolean can(OrgPermission permission) {
        return role.grants(permission);
    }
}
