package com.ecommerce.backend.catalog.service;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Image settings, bound from {@code app.files.*}.
 *
 * @param maxImagesPerProduct bounds storage per seller and the size of a storefront response
 */
@Validated
@ConfigurationProperties(prefix = "app.files")
public record ImageProperties(@Min(1) int maxImagesPerProduct) {
}
