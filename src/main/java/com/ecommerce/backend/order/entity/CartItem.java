package com.ecommerce.backend.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One line of an account's cart: a product and a quantity, never a price. Written only through the
 * repository's upsert, so this entity is read-only in practice.
 */
@Entity
@Table(name = "cart_items")
@IdClass(CartItem.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem {

    /** Composite primary key (user_id, product_id). */
    public record Key(UUID userId, UUID productId) implements Serializable {
    }

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
