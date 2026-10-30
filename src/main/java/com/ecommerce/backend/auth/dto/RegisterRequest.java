package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.service.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/register}.
 *
 * <p>Only these two fields can be bound. Status, roles or verification flags cannot be smuggled
 * in, because they do not exist here (mass-assignment protection), and unknown JSON fields are
 * rejected globally.
 */
@Schema(description = "New account credentials")
public record RegisterRequest(
        @Schema(example = "alice@example.com", maxLength = 254)
        @NotBlank @Email @Size(max = 254)
        String email,

        @Schema(example = "Correct-Horse-9-Battery",
                minLength = PasswordPolicy.MIN_LENGTH, maxLength = PasswordPolicy.MAX_LENGTH,
                description = "12 to 128 characters; not your email; not a common password")
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
        String password) {

    /** Trim surrounding spaces so " alice@example.com" from a copy-paste is still valid. */
    public RegisterRequest {
        email = email == null ? null : email.strip();
    }

    /** Records print every field by default; never let a password reach a log. */
    @Override
    public String toString() {
        return "RegisterRequest[email=<masked>, password=<hidden>]";
    }
}
