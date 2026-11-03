package com.ecommerce.backend.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Your email preferences. Account security emails are always sent")
public record PreferencesResponse(
        @Schema(example = "true") boolean orderUpdatesEmail,
        @Schema(example = "true") boolean sellerOrdersEmail) {
}
