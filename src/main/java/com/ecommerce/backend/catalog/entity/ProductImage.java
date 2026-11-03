package com.ecommerce.backend.catalog.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Metadata of one product image; the bytes are in {@code FileStorage} under {@link #storageKey}. Immutable. */
@Entity
@Table(name = "product_images")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage extends BaseEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 200)
    private String storageKey;

    @Column(name = "content_type", nullable = false, updatable = false, length = 20)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(name = "width", nullable = false, updatable = false)
    private int width;

    @Column(name = "height", nullable = false, updatable = false)
    private int height;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ProductImage of(UUID productId, UUID organizationId, String storageKey, String contentType,
                                  int sizeBytes, int width, int height) {
        ProductImage image = new ProductImage();
        image.productId = Objects.requireNonNull(productId, "productId");
        image.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        image.storageKey = Objects.requireNonNull(storageKey, "storageKey");
        image.contentType = Objects.requireNonNull(contentType, "contentType");
        image.sizeBytes = sizeBytes;
        image.width = width;
        image.height = height;
        return image;
    }
}
