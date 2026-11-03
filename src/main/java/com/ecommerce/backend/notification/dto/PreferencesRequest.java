package com.ecommerce.backend.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/v1/notifications/preferences}. Account security emails cannot be switched off. */
@Schema(description = "Which optional emails you want")
public record PreferencesRequest(
        @Schema(example = "true", description = "Your orders: confirmed, shipped, cancelled by the seller")
        @NotNull Boolean orderUpdatesEmail,

        @Schema(example = "true", description = "For seller staff: a new order to ship")
        @NotNull Boolean sellerOrdersEmail) {
}
