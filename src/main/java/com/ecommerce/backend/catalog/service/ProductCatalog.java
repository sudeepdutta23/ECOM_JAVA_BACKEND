package com.ecommerce.backend.catalog.service;

import com.ecommerce.backend.catalog.ProductSnapshot;
import com.ecommerce.backend.catalog.entity.Product;
import com.ecommerce.backend.catalog.repository.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue's public API for buying: current prices and availability, and the stock
 * reservation that checkout (step 6.3) relies on. The order module never touches products directly.
 */
@Service
public class ProductCatalog {

    private final ProductRepository products;
    private final Clock clock;

    public ProductCatalog(ProductRepository products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    /** Current state of these products, on sale or not. Unknown IDs are absent. One query. */
    @Transactional(readOnly = true)
    public Map<UUID, ProductSnapshot> find(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return products.findAllById(productIds).stream().map(ProductCatalog::snapshot)
                .collect(Collectors.toUnmodifiableMap(ProductSnapshot::id, Function.identity()));
    }

    /**
     * Takes {@code quantity} units out of stock in one statement, only if the product is on sale and
     * enough is left. Joins the caller's transaction: if checkout fails later, the units come back
     * with the rollback.
     *
     * @return false if the product is not on sale or has fewer units
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserve(UUID productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        return products.reserve(productId, quantity, Instant.now(clock)) == 1;
    }

    /** Puts units back, for a cancelled order. Works whatever the product's status is now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        products.release(productId, quantity, Instant.now(clock));
    }

    private static ProductSnapshot snapshot(Product product) {
        return new ProductSnapshot(product.getId(), product.getOrganizationId(), product.getSku(), product.getName(),
                product.getPriceMinor(), product.getCurrency(), product.getStock(), product.isOnSale());
    }
}
