package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.service.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/login}.
 *
 * <p>No email format or password strength rules here: login must accept whatever was valid when
 * the account was created, and a format error would tell an attacker more than "invalid
 * credentials". Only size limits remain, to bound the work the server does.
 */
@Schema(description = "Login credentials")
public record LoginRequest(
        @Schema(example = "alice@example.com", maxLength = 254)
        @NotBlank @Size(max = 254)
        String email,

        @Schema(example = "Correct-Horse-9-Battery", maxLength = PasswordPolicy.MAX_LENGTH)
        @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH)
        String password) {

    public LoginRequest {
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        return "LoginRequest[email=<masked>, password=<hidden>]";
    }
}
