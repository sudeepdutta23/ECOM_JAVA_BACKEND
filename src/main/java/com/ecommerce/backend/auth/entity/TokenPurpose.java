package com.ecommerce.backend.auth.entity;

/** What a one-time account token may be used for. Values must match {@code ck_account_tokens_purpose}. */
public enum TokenPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RESET
}
