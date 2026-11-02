package com.ecommerce.backend.org.dto;

import com.ecommerce.backend.org.OrgRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/organizations/{orgId}/invitations}. */
@Schema(description = "Invite an email address to join the organisation")
public record InviteMemberRequest(
        @Schema(example = "bob@example.com", description = "Need not have an account yet")
        @NotBlank @Email @Size(max = 254)
        String email,

        @Schema(example = "STAFF", description = "Below your own role; owners may also invite owners")
        @NotNull
        OrgRole role) {

    public InviteMemberRequest {
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        return "InviteMemberRequest[email=<masked>, role=" + role + "]";
    }
}
