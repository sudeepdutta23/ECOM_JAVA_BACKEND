package com.ecommerce.backend.catalog;

import java.util.UUID;

/**
 * A product as other modules see it (the cart, checkout): what it costs right now and whether it
 * can be bought. Exact stock is included for the server's own checks; it never reaches customers.
 *
 * @param onSale ACTIVE and the seller is ACTIVE
 */
public record ProductSnapshot(UUID id, UUID organizationId, String sku, String name, long priceMinor, String currency,
                              int stock, boolean onSale) {

    /** Whether {@code quantity} units can be bought now. */
    public boolean canSupply(int quantity) {
        return onSale && stock >= quantity;
    }
}
