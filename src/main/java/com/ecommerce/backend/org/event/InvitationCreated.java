package com.ecommerce.backend.org.event;

import com.ecommerce.backend.org.OrgRole;
import java.time.Instant;

/**
 * Someone was invited to an organisation. Carries the raw link token, which exists only here and
 * in the email: the database holds its hash.
 */
public record InvitationCreated(String email, String organizationName, OrgRole role, String rawToken,
                                Instant expiresAt) {

    /** Neither the address nor the token may reach a log. */
    @Override
    public String toString() {
        return "InvitationCreated[role=" + role + "]";
    }
}
