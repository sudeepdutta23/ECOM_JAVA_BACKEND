package com.ecommerce.backend.org;

/** Seller lifecycle. Values must match {@code ck_organizations_status} (V7). */
public enum OrganizationStatus {
    /** Created by a user; members can set it up, but it cannot sell until the platform approves it. */
    PENDING_APPROVAL,
    /** Approved. Can sell (Phase 6). */
    ACTIVE,
    /** Blocked by the platform, with an internal reason. Members can sign in but not act for it. */
    SUSPENDED,
    /** Closed by an owner or the platform. Terminal: kept for order history, never reopened. */
    CLOSED
}
