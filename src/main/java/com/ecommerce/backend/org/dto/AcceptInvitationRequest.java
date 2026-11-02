package com.ecommerce.backend.org.dto;

import com.ecommerce.backend.common.security.OpaqueTokens;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body of {@code POST /api/v1/organizations/invitations/accept}: the token from the email link. */
@Schema(description = "The token from the invitation link")
public record AcceptInvitationRequest(
        @Schema(example = "q2vQ8m1f0xY3bS9kLw7pT4rN6uC5aE2dH8jK1zR0sVg")
        @NotBlank @Pattern(regexp = OpaqueTokens.FORMAT_REGEX, message = "is not a valid invitation token")
        String token) {

    @Override
    public String toString() {
        return "AcceptInvitationRequest[token=<hidden>]";
    }
}
