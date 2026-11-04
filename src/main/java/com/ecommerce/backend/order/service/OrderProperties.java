package com.ecommerce.backend.order.service;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Order settings, bound from {@code app.order.*}.
 *
 * @param maxCartLines   different products one cart may hold: bounds the cart view (no paging) and a
 *                       checkout's size. A bound, not an invariant: two parallel adds at the limit may
 *                       both pass, which harms nobody.
 * @param paymentTimeout how long an order waits for payment before the platform cancels it and its
 *                       stock goes back on sale (Phase 12)
 */
@Validated
@ConfigurationProperties(prefix = "app.order")
public record OrderProperties(@Min(1) int maxCartLines, @NotNull Duration paymentTimeout) {
}
