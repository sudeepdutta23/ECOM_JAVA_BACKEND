package com.ecommerce.backend.notification.repository;

import com.ecommerce.backend.notification.entity.NotificationPreferences;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationPreferencesRepository extends JpaRepository<NotificationPreferences, UUID> {

    /** Erasure. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from NotificationPreferences p where p.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
