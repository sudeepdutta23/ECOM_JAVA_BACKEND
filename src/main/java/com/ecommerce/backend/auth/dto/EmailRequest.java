package com.ecommerce.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body carrying only an email: resend verification now, forgot password in Step 1.7. */
@Schema(description = "An email address")
public record EmailRequest(
        @Schema(example = "alice@example.com", maxLength = 254)
        @NotBlank @Email @Size(max = 254)
        String email) {

    public EmailRequest {
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        return "EmailRequest[email=<masked>]";
    }
}
