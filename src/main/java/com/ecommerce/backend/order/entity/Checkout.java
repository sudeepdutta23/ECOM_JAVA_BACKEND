package com.ecommerce.backend.order.entity;

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

/**
 * One successful checkout: what the customer paid for in one go, across sellers. Its
 * {@code idempotencyKey} makes a retried request return this checkout instead of buying again.
 */
@Entity
@Table(name = "checkouts")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Checkout extends BaseEntity {

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "total_minor", nullable = false, updatable = false)
    private long totalMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "paid_at")
    private Instant paidAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Checkout of(UUID customerId, String idempotencyKey, long totalMinor, String currency) {
        Checkout checkout = new Checkout();
        checkout.customerId = Objects.requireNonNull(customerId, "customerId");
        checkout.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        checkout.totalMinor = totalMinor;
        checkout.currency = Objects.requireNonNull(currency, "currency");
        return checkout;
    }

    public boolean isPaid() {
        return paidAt != null;
    }

    public void recordPayment(String reference, Instant now) {
        if (isPaid()) {
            throw new IllegalStateException("Already paid");
        }
        this.paymentReference = Objects.requireNonNull(reference, "reference");
        this.paidAt = Objects.requireNonNull(now, "now");
    }
}
