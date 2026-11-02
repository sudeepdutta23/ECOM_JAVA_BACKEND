package com.ecommerce.backend.auth.entity;

/** Account lifecycle. Values must match {@code ck_users_status} (V2, widened in V4 and V5). */
public enum UserStatus {
    /** Registered, email not yet confirmed. Cannot log in. */
    PENDING_VERIFICATION,
    /** Email confirmed. Can log in unless temporarily locked. */
    ACTIVE,
    /** Closed by the owner. Cannot log in. Only an administrator can reactivate it. */
    DEACTIVATED,
    /** Blocked by an administrator, with an internal reason. Cannot log in. */
    SUSPENDED,
    /**
     * Anonymised on the owner's request. Terminal: no transition leads out of it. The row stays so
     * that references to it remain valid; it holds no personal data (CHECK in V5).
     */
    ERASED
}
