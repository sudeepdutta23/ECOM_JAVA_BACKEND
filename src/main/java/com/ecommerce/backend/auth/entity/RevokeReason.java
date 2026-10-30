package com.ecommerce.backend.auth.entity;

/**
 * Why a refresh token stopped being valid. Stored for investigations, for example to tell
 * a normal logout from a detected token theft. Values must match {@code ck_refresh_tokens_revoke_reason}.
 */
public enum RevokeReason {
    ROTATED,
    LOGOUT,
    LOGOUT_ALL,
    REUSE_DETECTED,
    PASSWORD_CHANGED,
    PASSWORD_RESET,
    ACCOUNT_DEACTIVATED
}
