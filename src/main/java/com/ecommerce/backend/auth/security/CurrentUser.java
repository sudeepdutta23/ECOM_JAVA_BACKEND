package com.ecommerce.backend.auth.security;

import com.ecommerce.backend.common.security.TokenClaims;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the signed-in user's ID from a verified access token.
 *
 * <p>The ID always comes from the token's {@code sub} claim, never from a request body or path.
 * Otherwise a user could act on someone else's account by changing an ID (an IDOR bug).
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** The session (login) this token belongs to; null for tokens issued before the claim existed. */
    public static UUID sessionId(Jwt jwt) {
        String sid = jwt.getClaimAsString(TokenClaims.SESSION_ID);
        return sid == null ? null : UUID.fromString(sid);
    }
}
