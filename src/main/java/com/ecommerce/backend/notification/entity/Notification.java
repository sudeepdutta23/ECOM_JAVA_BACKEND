package com.ecommerce.backend.notification.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import com.ecommerce.backend.notification.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** One entry in a person's in-app inbox. Text is rendered when written; only {@code readAt} changes later. */
@Entity
@Table(name = "notifications")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 40)
    private NotificationType type;

    @Column(name = "title", nullable = false, updatable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, updatable = false, length = 1000)
    private String body;

    @Column(name = "link", nullable = false, updatable = false, length = 300)
    private String link;

    @Column(name = "read_at")
    private Instant readAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Notification of(UUID userId, NotificationType type, String title, String body, String link) {
        Notification notification = new Notification();
        notification.userId = Objects.requireNonNull(userId, "userId");
        notification.type = Objects.requireNonNull(type, "type");
        notification.title = Objects.requireNonNull(title, "title");
        notification.body = Objects.requireNonNull(body, "body");
        notification.link = Objects.requireNonNull(link, "link");
        return notification;
    }

    @Override
    public String toString() {
        return "Notification[id=" + getId() + ", userId=" + userId + ", type=" + type + "]";
    }
}
