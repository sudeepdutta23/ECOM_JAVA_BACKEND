package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.security.OpaqueTokens;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body carrying a one-time token from an email link.
 *
 * <p>The format check rejects junk before any database work, and keeps arbitrary strings out of
 * queries and logs.
 */
@Schema(description = "One-time token from an email link")
public record TokenRequest(
        @Schema(example = "q2vQ8m1f0xY3bS9kLw7pT4rN6uC5aE2dH8jK1zR0sVg", minLength = 43, maxLength = 43)
        @NotBlank @Pattern(regexp = OpaqueTokens.FORMAT_REGEX, message = "must be a valid token")
        String token) {

    @Override
    public String toString() {
        return "TokenRequest[token=<hidden>]";
    }
}
