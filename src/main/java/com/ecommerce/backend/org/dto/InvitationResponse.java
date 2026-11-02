package com.ecommerce.backend.org.dto;

import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.entity.OrganizationInvitation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** A pending invitation as members with MEMBER_MANAGE see it. The token is never part of it. */
@Schema(description = "A pending invitation")
public record InvitationResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "bob@example.com") String email,
        @Schema(example = "STAFF") OrgRole role,
        UUID invitedBy,
        Instant createdAt,
        Instant expiresAt) {

    public static InvitationResponse from(OrganizationInvitation invitation) {
        return new InvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRole(),
                invitation.getInvitedBy(), invitation.getCreatedAt(), invitation.getExpiresAt());
    }
}
