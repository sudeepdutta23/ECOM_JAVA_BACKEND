package com.ecommerce.backend.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One line of an order: the product as it was sold, name and unit price copied at checkout. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLine {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "sku", nullable = false, updatable = false, length = 64)
    private String sku;

    @Column(name = "name", nullable = false, updatable = false, length = 200)
    private String name;

    @Column(name = "unit_price_minor", nullable = false, updatable = false)
    private long unitPriceMinor;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    public OrderLine(UUID productId, String sku, String name, long unitPriceMinor, int quantity) {
        this.productId = Objects.requireNonNull(productId, "productId");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.name = Objects.requireNonNull(name, "name");
        this.unitPriceMinor = unitPriceMinor;
        this.quantity = quantity;
    }

    public long totalMinor() {
        return Math.multiplyExact(unitPriceMinor, quantity);
    }
}
