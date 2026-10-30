package com.ecommerce.backend.auth.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for access tokens, bound from {@code app.security.jwt.*}.
 *
 * <p>A record with {@code @ConfigurationProperties} is immutable, and {@code @Validated} makes
 * the application refuse to start when a required value is missing or invalid. That is far
 * better than discovering a missing secret on the first login in production.
 *
 * @param issuer             value of the {@code iss} claim; tokens with another issuer are rejected
 * @param accessTokenTtl     access token lifetime
 * @param clockSkew          tolerance for small clock differences when checking {@code exp}
 * @param privateKeyLocation PKCS#8 PEM file, e.g. {@code file:/run/secrets/jwt-private.pem}; blank = none
 * @param keyId              {@code kid} header; blank = derive from the key (RFC 7638 thumbprint)
 * @param allowEphemeralKey  generate a throwaway key when none is configured (dev and test only)
 */
@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration clockSkew,
        String privateKeyLocation,
        String keyId,
        boolean allowEphemeralKey) {
}
