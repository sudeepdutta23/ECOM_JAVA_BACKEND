package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.service.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/password/change}. */
@Schema(description = "Current and new password")
public record ChangePasswordRequest(
        @Schema(example = "Correct-Horse-9-Battery", maxLength = PasswordPolicy.MAX_LENGTH)
        @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH)
        String currentPassword,

        @Schema(example = "Even-Better-Passphrase-42",
                minLength = PasswordPolicy.MIN_LENGTH, maxLength = PasswordPolicy.MAX_LENGTH)
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
        String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[<hidden>]";
    }
}
