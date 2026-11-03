package com.ecommerce.backend.notification.repository;

import com.ecommerce.backend.notification.entity.Notification;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query is scoped by the inbox's owner. */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Served by {@code idx_notifications_user_id}. */
    Page<Notification> findByUserId(UUID userId, Pageable page);

    Page<Notification> findByUserIdAndReadAtIsNull(UUID userId, Pageable page);

    /** The unread badge. Served by the partial {@code idx_notifications_unread}. */
    long countByUserIdAndReadAtIsNull(UUID userId);

    /** Idempotent: an already read notification keeps its first read time. Zero rows for someone else's ID. */
    @Modifying
    @Query("update Notification n set n.readAt = :now where n.id = :id and n.userId = :userId and n.readAt is null")
    int markRead(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    boolean existsByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Erasure. Flushes the erasure's pending changes first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Notification n where n.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
