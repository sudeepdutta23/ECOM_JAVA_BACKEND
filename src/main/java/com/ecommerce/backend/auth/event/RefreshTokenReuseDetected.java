package com.ecommerce.backend.auth.event;

import java.util.UUID;

/**
 * An already-rotated refresh token was presented again.
 *
 * <p>A legitimate client never does this: it always holds the newest token. So either the token
 * was stolen and the thief (or the victim) is now using the old copy. We cannot tell who is who,
 * so the whole session family is revoked and the owner is told to sign in again.
 */
public record RefreshTokenReuseDetected(String email, UUID familyId) {

    @Override
    public String toString() {
        return "RefreshTokenReuseDetected[familyId=" + familyId + "]";
    }
}
