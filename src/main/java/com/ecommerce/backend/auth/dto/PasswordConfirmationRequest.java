package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.service.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Re-confirmation of the current password before a sensitive action. */
@Schema(description = "Current password, to confirm a sensitive action")
public record PasswordConfirmationRequest(
        @Schema(example = "Correct-Horse-9-Battery", maxLength = PasswordPolicy.MAX_LENGTH)
        @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH)
        String password) {

    @Override
    public String toString() {
        return "PasswordConfirmationRequest[<hidden>]";
    }
}
