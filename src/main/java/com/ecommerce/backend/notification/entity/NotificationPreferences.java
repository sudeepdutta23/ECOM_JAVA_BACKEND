package com.ecommerce.backend.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Which optional emails a person wants. No row means the defaults (everything on), so most accounts
 * never have one. Account security emails (verification, password reset, lockout) are not here:
 * they cannot be switched off.
 */
@Entity
@Table(name = "notification_preferences")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreferences {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "order_updates_email", nullable = false)
    private boolean orderUpdatesEmail;

    @Column(name = "seller_orders_email", nullable = false)
    private boolean sellerOrdersEmail;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static NotificationPreferences defaults(UUID userId) {
        NotificationPreferences preferences = new NotificationPreferences();
        preferences.userId = Objects.requireNonNull(userId, "userId");
        preferences.orderUpdatesEmail = true;
        preferences.sellerOrdersEmail = true;
        return preferences;
    }

    public void change(boolean orderUpdatesEmail, boolean sellerOrdersEmail) {
        this.orderUpdatesEmail = orderUpdatesEmail;
        this.sellerOrdersEmail = sellerOrdersEmail;
    }
}
