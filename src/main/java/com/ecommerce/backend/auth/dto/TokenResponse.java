package com.ecommerce.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Access token returned by login and refresh. Field names follow OAuth2 conventions
 * (camelCase variant), so client libraries feel familiar.
 *
 * <p>The refresh token is NOT here: it travels only in the HttpOnly cookie.
 */
@Schema(description = "Access token for the Authorization header")
public record TokenResponse(
        @Schema(example = "eyJraWQiOiJ...") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(example = "900", description = "Seconds until the access token expires") long expiresIn) {

    public static TokenResponse bearer(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn);
    }

    @Override
    public String toString() {
        return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
