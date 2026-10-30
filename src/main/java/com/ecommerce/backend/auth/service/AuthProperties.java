package com.ecommerce.backend.auth.service;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Auth module settings, bound from {@code app.auth.*}.
 *
 * @param frontendBaseUrl      where email links point; the frontend page then calls the API
 * @param emailVerificationTtl how long a verification link stays valid
 * @param refreshTokenTtl      how long a refresh token (and its cookie) stays valid
 * @param maxFailedLogins      consecutive wrong passwords before a temporary lock
 * @param lockoutDuration      how long the lock lasts
 * @param passwordResetTtl     how long a password reset link stays valid; short, because it grants access
 * @param failedLoginMinDuration every failed login takes at least this long, so response time
 *                             cannot reveal which path ran (and thus whether the email exists)
 */
@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @NotBlank String frontendBaseUrl,
        @NotNull Duration emailVerificationTtl,
        @NotNull Duration refreshTokenTtl,
        @Min(1) int maxFailedLogins,
        @NotNull Duration lockoutDuration,
        @NotNull Duration passwordResetTtl,
        @NotNull Duration failedLoginMinDuration) {
}
