package com.ecommerce.backend.notification.service;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Notification settings, bound from {@code app.notification.*}.
 *
 * @param frontendBaseUrl where email links point; each notification's link is a path inside the app
 */
@Validated
@ConfigurationProperties(prefix = "app.notification")
public record NotificationProperties(@NotBlank String frontendBaseUrl) {
}
