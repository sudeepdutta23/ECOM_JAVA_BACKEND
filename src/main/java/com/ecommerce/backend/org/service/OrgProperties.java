package com.ecommerce.backend.org.service;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Organisation settings, bound from {@code app.org.*}.
 *
 * @param maxOwnedOrganizations organisations one account may own that are not closed
 * @param maxPendingInvitations open, unexpired invitations per organisation: each one sent an email,
 *                              so this bounds how much mail one organisation can trigger
 * @param invitationTtl         how long an invitation link stays valid
 * @param frontendBaseUrl       where invitation links point; the frontend page then calls the API
 */
@Validated
@ConfigurationProperties(prefix = "app.org")
public record OrgProperties(
        @Min(1) int maxOwnedOrganizations,
        @Min(1) int maxPendingInvitations,
        @NotNull Duration invitationTtl,
        @NotBlank String frontendBaseUrl) {
}
