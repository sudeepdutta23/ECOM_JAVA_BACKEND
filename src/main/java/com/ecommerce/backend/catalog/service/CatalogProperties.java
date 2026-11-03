package com.ecommerce.backend.catalog.service;

import jakarta.validation.constraints.NotBlank;
import java.util.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Catalogue settings, bound from {@code app.catalog.*}.
 *
 * @param currency the one platform currency (ISO 4217). Multi-currency needs prices per currency and
 *                 exchange rules; until then every product is priced in this one. Must have two decimal
 *                 places, because {@code MinorUnits} assumes cents: checked here, at startup.
 */
@Validated
@ConfigurationProperties(prefix = "app.catalog")
public record CatalogProperties(@NotBlank String currency) {

    public CatalogProperties {
        if (currency != null && Currency.getInstance(currency).getDefaultFractionDigits() != 2) {
            throw new IllegalArgumentException("app.catalog.currency must have two decimal places: " + currency);
        }
    }
}
