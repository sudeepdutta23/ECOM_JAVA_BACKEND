package com.ecommerce.backend.order.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import com.ecommerce.backend.order.OrderStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One seller's part of a checkout. Tenant data: {@code organizationId}, and seller-side lookups
 * always take it. Named {@code SalesOrder} because {@code ORDER} is a reserved word in JPQL and SQL;
 * the table is {@code orders}.
 *
 * <p>Status transitions are methods with their own preconditions, like {@code Organization}; see
 * {@link OrderStatus} for the diagram.
 */
@Entity
@Table(name = "orders")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesOrder extends BaseEntity {

    @Column(name = "checkout_id", nullable = false, updatable = false)
    private UUID checkoutId;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "seller_name", nullable = false, updatable = false, length = 100)
    private String sellerName;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "total_minor", nullable = false, updatable = false)
    private long totalMinor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address", nullable = false, updatable = false)
    private AddressSnapshot shippingAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "billing_address", nullable = false, updatable = false)
    private AddressSnapshot billingAddress;

    /** Written once, at checkout. {@code line_no} keeps the cart's order. */
    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<OrderLine> lines = new ArrayList<>();

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    /** Shown to the customer and the seller. Free text from whoever cancelled; never logged. */
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A new order waiting for payment. Its total is the sum of its lines, never a number from the client. */
    public static SalesOrder place(UUID checkoutId, UUID organizationId, String sellerName, UUID customerId, String currency,
                                   List<OrderLine> lines, AddressSnapshot shipping, AddressSnapshot billing) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        SalesOrder order = new SalesOrder();
        order.checkoutId = Objects.requireNonNull(checkoutId, "checkoutId");
        order.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        order.sellerName = Objects.requireNonNull(sellerName, "sellerName");
        order.customerId = Objects.requireNonNull(customerId, "customerId");
        order.currency = Objects.requireNonNull(currency, "currency");
        order.lines = new ArrayList<>(lines);
        order.totalMinor = lines.stream().mapToLong(OrderLine::totalMinor).reduce(0, Math::addExact);
        order.shippingAddress = Objects.requireNonNull(shipping, "shipping");
        order.billingAddress = Objects.requireNonNull(billing, "billing");
        order.status = OrderStatus.PENDING_PAYMENT;
        return order;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public void markPaid(Instant now) {
        requireStatus(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.PAID;
        this.paidAt = Objects.requireNonNull(now, "now");
    }

    public void ship(String trackingNumber, Instant now) {
        requireStatus(OrderStatus.PAID);
        this.status = OrderStatus.SHIPPED;
        this.trackingNumber = trackingNumber;
        this.shippedAt = Objects.requireNonNull(now, "now");
    }

    public void markDelivered(Instant now) {
        requireStatus(OrderStatus.SHIPPED);
        this.status = OrderStatus.DELIVERED;
        this.deliveredAt = Objects.requireNonNull(now, "now");
    }

    /** The caller returns the stock (it belongs to the catalogue). */
    public void cancel(UUID by, String reason, Instant now) {
        if (!status.isCancellable()) {
            throw new IllegalStateException("Status " + status + " cannot be cancelled");
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledBy = by;
        this.cancelReason = reason;
        this.cancelledAt = Objects.requireNonNull(now, "now");
    }

    private void requireStatus(OrderStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Status " + status + " does not allow this change");
        }
    }

    @Override
    public String toString() {
        return "SalesOrder[id=" + getId() + ", organizationId=" + organizationId + ", status=" + status + "]";
    }
}
