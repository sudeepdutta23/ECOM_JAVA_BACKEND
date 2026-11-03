package com.ecommerce.backend.order.repository;

import com.ecommerce.backend.order.entity.CartItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query is scoped by the cart's owner and served by {@code pk_cart_items (user_id, product_id)}. */
public interface CartItemRepository extends JpaRepository<CartItem, CartItem.Key> {

    /** In the order the customer added them. At most a few dozen rows, sorted in memory by PostgreSQL. */
    List<CartItem> findByUserIdOrderByAddedAtAscProductIdAsc(UUID userId);

    long countByUserId(UUID userId);

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    /**
     * Sets the quantity, adding the line if it is new, in one statement: a double click or two tabs
     * cannot create two lines for one product (the primary key), and neither loses the other's write.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO cart_items (user_id, product_id, quantity, added_at, updated_at)
            VALUES (:userId, :productId, :quantity, :now, :now)
            ON CONFLICT (user_id, product_id) DO UPDATE SET quantity = EXCLUDED.quantity, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int upsert(@Param("userId") UUID userId, @Param("productId") UUID productId, @Param("quantity") int quantity,
               @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("delete from CartItem c where c.userId = :userId and c.productId = :productId")
    int deleteLine(@Param("userId") UUID userId, @Param("productId") UUID productId);

    /** Emptying the cart, after checkout and at erasure. Flushes the caller's pending changes first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CartItem c where c.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
