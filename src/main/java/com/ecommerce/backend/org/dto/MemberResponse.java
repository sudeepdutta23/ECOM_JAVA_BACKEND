package com.ecommerce.backend.org.dto;

import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.entity.OrganizationMember;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** A colleague in the member list. The email comes from the auth module (UserDirectory). */
@Schema(description = "A member of the organisation")
public record MemberResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID userId,
        @Schema(example = "bob@example.com") String email,
        @Schema(example = "STAFF") OrgRole role,
        Instant joinedAt,
        @Schema(description = "Who invited them; null for the founder") UUID addedBy) {

    public static MemberResponse from(OrganizationMember member, String email) {
        return new MemberResponse(member.getUserId(), email, member.getRole(), member.getJoinedAt(), member.getAddedBy());
    }
}
