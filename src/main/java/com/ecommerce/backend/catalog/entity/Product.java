package com.ecommerce.backend.catalog.entity;

import com.ecommerce.backend.catalog.ProductStatus;
import com.ecommerce.backend.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A seller's product: tenant data, so it carries {@code organizationId} and every lookup takes it
 * (ADR-012). Stock changes go through atomic UPDATE statements in the repository, never through
 * read-modify-write on this entity, so parallel changes cannot lose each other.
 */
@Entity
@Table(name = "products")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

    /** What the seller writes, already normalized and validated. Price in minor units. */
    public record Details(String sku, String name, String description, long priceMinor, UUID categoryId) {
    }

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 5000)
    private String description;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    /** Read here, written only by the repository's atomic statements. */
    @Column(name = "stock", nullable = false, insertable = false, updatable = false)
    private int stock;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProductStatus status;

    /** Written on insert here, and afterwards only by the bulk update on a seller status change. */
    @Column(name = "seller_active", nullable = false, updatable = false)
    private boolean sellerActive;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A new product starts as a DRAFT with no stock: invisible until the seller publishes it. */
    public static Product draft(UUID organizationId, Details details, String currency, boolean sellerActive,
                                UUID createdBy) {
        Product product = new Product();
        product.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        product.currency = Objects.requireNonNull(currency, "currency");
        product.sellerActive = sellerActive;
        product.createdBy = createdBy;
        product.status = ProductStatus.DRAFT;
        product.change(details);
        return product;
    }

    public void change(Details details) {
        Objects.requireNonNull(details, "details");
        if (details.priceMinor() <= 0) {
            throw new IllegalArgumentException("A price must be positive");
        }
        this.sku = Objects.requireNonNull(details.sku(), "sku");
        this.name = Objects.requireNonNull(details.name(), "name");
        this.description = details.description();
        this.priceMinor = details.priceMinor();
        this.categoryId = details.categoryId();
    }

    /** From DRAFT or ARCHIVED onto the storefront (if the seller is active). */
    public void publish() {
        if (status == ProductStatus.ACTIVE) {
            throw new IllegalStateException("Already published");
        }
        this.status = ProductStatus.ACTIVE;
    }

    /** Off the storefront; stays resolvable for order history. */
    public void archive() {
        if (status == ProductStatus.ARCHIVED) {
            throw new IllegalStateException("Already archived");
        }
        this.status = ProductStatus.ARCHIVED;
    }

    /** What customers can see right now. */
    public boolean isOnSale() {
        return status == ProductStatus.ACTIVE && sellerActive;
    }

    @Override
    public String toString() {
        return "Product[id=" + getId() + ", organizationId=" + organizationId + ", status=" + status + "]";
    }
}
