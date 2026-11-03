package com.ecommerce.backend.notification.service;

import com.ecommerce.backend.notification.repository.NotificationPreferencesRepository;
import com.ecommerce.backend.notification.repository.NotificationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Erasure: the inbox and the preferences go in the erasure's transaction, like the profile. */
@Service
public class NotificationEraser {

    private final NotificationRepository notifications;
    private final NotificationPreferencesRepository preferences;

    public NotificationEraser(NotificationRepository notifications, NotificationPreferencesRepository preferences) {
        this.notifications = notifications;
        this.preferences = preferences;
    }

    /** @return the number of notifications removed */
    @Transactional(propagation = Propagation.MANDATORY)
    public int forget(UUID userId) {
        preferences.deleteByUserId(userId);
        return notifications.deleteAllByUserId(userId);
    }
}
