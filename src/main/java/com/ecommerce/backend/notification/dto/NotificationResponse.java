package com.ecommerce.backend.notification.dto;

import com.ecommerce.backend.notification.NotificationType;
import com.ecommerce.backend.notification.entity.Notification;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An in-app notification")
public record NotificationResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "ORDER_SHIPPED") NotificationType type,
        @Schema(example = "Your order is on its way") String title,
        @Schema(example = "Acme Store shipped your order.") String body,
        @Schema(example = "/orders/01a1868e-9cf4-79d1-b09b-dedd85868415", description = "A path inside the app")
        String link,
        @Schema(example = "false") boolean read,
        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getType(), notification.getTitle(),
                notification.getBody(), notification.getLink(), notification.getReadAt() != null,
                notification.getCreatedAt());
    }
}
