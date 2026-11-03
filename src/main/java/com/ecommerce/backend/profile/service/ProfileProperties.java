package com.ecommerce.backend.profile.service;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Profile settings, bound from {@code app.profile.*}.
 *
 * @param maxAddresses addresses one account may save: enough for any household, and it bounds the
 *                     list (no paging) and what a scripted account can store
 */
@Validated
@ConfigurationProperties(prefix = "app.profile")
public record ProfileProperties(@Min(1) int maxAddresses) {
}
