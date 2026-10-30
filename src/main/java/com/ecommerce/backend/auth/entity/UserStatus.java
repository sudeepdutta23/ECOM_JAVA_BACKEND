package com.ecommerce.backend.auth.entity;

/** Account lifecycle. Values must match {@code ck_users_status} in V2. */
public enum UserStatus {
    /** Registered, email not yet confirmed. Cannot log in. */
    PENDING_VERIFICATION,
    /** Email confirmed. Can log in unless temporarily locked. */
    ACTIVE,
    /** Switched off by the user (or an admin in Phase 3). Cannot log in. */
    DEACTIVATED
}
