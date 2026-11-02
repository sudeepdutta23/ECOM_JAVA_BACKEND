package com.ecommerce.backend.org;

/**
 * What a member may do inside their own organisation. Not stored anywhere: each {@link OrgRole}
 * maps to a fixed set in code. Phase 6 adds catalogue and order permissions here.
 *
 * <p>Deliberately separate from the platform's {@code rbac.Permission}: those are about the whole
 * marketplace and travel in the access token; these are about one seller and are checked against
 * the membership row on every request (ADR-012).
 */
public enum OrgPermission {
    /** See the organisation's settings and its member list. */
    ORGANIZATION_READ,
    /** Change the organisation's name and settings. */
    ORGANIZATION_UPDATE,
    /** Invite, re-role and remove members ranked below you. */
    MEMBER_MANAGE,
    /** Close the organisation for good. */
    ORGANIZATION_CLOSE
}
