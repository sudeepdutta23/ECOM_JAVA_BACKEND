package com.ecommerce.backend.catalog.repository;

import com.ecommerce.backend.catalog.entity.ProductImage;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Seller lookups take the organisation; the storefront takes the product, after checking it is on sale. */
public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    /** Served by {@code idx_product_images_product_id}; upload order. */
    List<ProductImage> findByProductIdOrderByIdAsc(UUID productId);

    /** A storefront page's images in one query. */
    List<ProductImage> findByProductIdInOrderByIdAsc(Collection<UUID> productIds);

    Optional<ProductImage> findByIdAndProductIdAndOrganizationId(UUID id, UUID productId, UUID organizationId);

    Optional<ProductImage> findByIdAndProductId(UUID id, UUID productId);

    long countByProductId(UUID productId);
}
