package com.ecommerce.backend.catalog.repository;

import com.ecommerce.backend.catalog.ProductStatus;
import com.ecommerce.backend.catalog.entity.Product;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Seller-side lookups always take the organisation ID (ADR-012): a product ID of another seller is
 * "not found", even inside a correctly guarded call. The storefront uses {@link StorefrontSearch}.
 */
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Seller's list. Served by {@code idx_products_organization_id}. */
    Page<Product> findByOrganizationId(UUID organizationId, Pageable page);

    Page<Product> findByOrganizationIdAndStatus(UUID organizationId, ProductStatus status, Pageable page);

    boolean existsByOrganizationIdAndSku(UUID organizationId, String sku);

    boolean existsByCategoryId(UUID categoryId);

    /**
     * Adds {@code delta} (negative to remove) in one statement, never below 0 or above the CHECK's
     * maximum. Zero rows means: no such product of this seller, or the stock would leave the range.
     * Bumps the version, so an open edit form notices that the product changed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Product p set p.stock = p.stock + :delta, p.version = p.version + 1, p.updatedAt = :now
            where p.id = :id and p.organizationId = :organizationId
              and p.stock + :delta >= 0 and p.stock + :delta <= 10000000
            """)
    int adjustStock(@Param("id") UUID id, @Param("organizationId") UUID organizationId, @Param("delta") int delta,
                    @Param("now") Instant now);

    /**
     * Checkout: takes units out of stock only if the product is on sale and enough is left, in one
     * statement. The row lock it takes is held until the checkout commits, so two customers buying
     * the last unit cannot both succeed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Product p set p.stock = p.stock - :quantity, p.version = p.version + 1, p.updatedAt = :now
            where p.id = :id and p.status = com.ecommerce.backend.catalog.ProductStatus.ACTIVE
              and p.sellerActive = true and p.stock >= :quantity
            """)
    int reserve(@Param("id") UUID id, @Param("quantity") int quantity, @Param("now") Instant now);

    /** A cancelled order gives its units back, capped at the CHECK's maximum. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Product p set p.stock = least(p.stock + :quantity, 10000000), p.version = p.version + 1,
                   p.updatedAt = :now
            where p.id = :id
            """)
    int release(@Param("id") UUID id, @Param("quantity") int quantity, @Param("now") Instant now);

    /**
     * Keeps {@code seller_active} in step with the seller's status, inside the status change's
     * transaction. No clear: the caller's organisation entity must stay managed.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.sellerActive = :active where p.organizationId = :organizationId and p.sellerActive <> :active")
    int updateSellerActive(@Param("organizationId") UUID organizationId, @Param("active") boolean active);
}
