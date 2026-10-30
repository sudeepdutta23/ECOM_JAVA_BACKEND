package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.security.OpaqueTokens;
import com.ecommerce.backend.auth.service.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/password/reset}. */
@Schema(description = "Reset token from the email plus the new password")
public record ResetPasswordRequest(
        @Schema(example = "q2vQ8m1f0xY3bS9kLw7pT4rN6uC5aE2dH8jK1zR0sVg", minLength = 43, maxLength = 43)
        @NotBlank @Pattern(regexp = OpaqueTokens.FORMAT_REGEX, message = "must be a valid token")
        String token,

        @Schema(example = "Even-Better-Passphrase-42",
                minLength = PasswordPolicy.MIN_LENGTH, maxLength = PasswordPolicy.MAX_LENGTH)
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
        String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[<hidden>]";
    }
}
