package com.ecommerce.backend.common.security;

/**
 * Names of the custom claims in our access tokens, shared by the code that writes them
 * ({@code AccessTokenIssuer}) and the code that reads them ({@code SecurityConfig}).
 */
public final class TokenClaims {

    /** Role codes, e.g. {@code ["CUSTOMER"]}. Informational; authorization checks permissions. */
    public static final String ROLES = "roles";

    /** Resolved permission codes, e.g. {@code ["role:read"]}. What {@code @PreAuthorize} checks. */
    public static final String PERMISSIONS = "perms";

    /**
     * Session ID: the refresh-token family the access token was issued for. Same name as the OpenID
     * Connect {@code sid} claim. Lets {@code GET /me/sessions} mark the caller's own session.
     */
    public static final String SESSION_ID = "sid";

    private TokenClaims() {
    }
}
