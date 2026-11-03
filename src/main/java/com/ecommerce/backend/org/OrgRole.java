package com.ecommerce.backend.org;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A member's role in one organisation. Values must match {@code ck_organization_members_role} (V7).
 *
 * <p><b>Why a fixed set instead of custom roles per organisation?</b> Small sellers need three
 * levels, and a fixed set keeps every authorization decision readable in one place. Custom
 * per-organisation roles would need their own tables, an editor and the escalation rules of Phase 2
 * once per seller. They can be added later without changing the permission checks, which only ask
 * {@link #grants}.
 */
public enum OrgRole {

    /** Full control, including closing the organisation and managing other owners. */
    OWNER(3, EnumSet.allOf(OrgPermission.class)),
    /** Runs the shop day to day and manages staff, but cannot close it or touch owners. */
    MANAGER(2, EnumSet.of(OrgPermission.ORGANIZATION_READ, OrgPermission.ORGANIZATION_UPDATE,
            OrgPermission.MEMBER_MANAGE, OrgPermission.CATALOG_MANAGE, OrgPermission.ORDER_MANAGE)),
    /** Works in the shop: catalogue and orders (Phase 6). Sees the organisation but cannot change it. */
    STAFF(1, EnumSet.of(OrgPermission.ORGANIZATION_READ, OrgPermission.CATALOG_MANAGE, OrgPermission.ORDER_MANAGE));

    private final int rank;
    private final Set<OrgPermission> permissions;

    OrgRole(int rank, Set<OrgPermission> permissions) {
        this.rank = rank;
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    /**
     * The rank rule: you may invite into, assign, and act on members holding a role strictly below
     * your own; only an owner may also do so for owners. A manager cannot promote anyone to manager
     * (themselves included) or touch an owner. Callers check {@link OrgPermission#MEMBER_MANAGE} first.
     */
    public boolean canManage(OrgRole other) {
        return this == OWNER || rank > other.rank;
    }

    public boolean grants(OrgPermission permission) {
        return permissions.contains(permission);
    }

    public Set<OrgPermission> permissions() {
        return permissions;
    }
}
