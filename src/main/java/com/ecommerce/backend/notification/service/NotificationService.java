package com.ecommerce.backend.notification.service;

import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.notification.dto.NotificationResponse;
import com.ecommerce.backend.notification.dto.PreferencesResponse;
import com.ecommerce.backend.notification.entity.NotificationPreferences;
import com.ecommerce.backend.notification.repository.NotificationPreferencesRepository;
import com.ecommerce.backend.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The signed-in person's inbox and email preferences; the account always comes from the token. */
@Service
public class NotificationService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    private final NotificationRepository notifications;
    private final NotificationPreferencesRepository preferences;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, NotificationPreferencesRepository preferences,
                               Clock clock) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID userId, boolean unreadOnly, int page, int size) {
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        return PageResponse.from(unreadOnly ? notifications.findByUserIdAndReadAtIsNull(userId, request)
                : notifications.findByUserId(userId, request), NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    /** Idempotent for the owner; someone else's notification is 404, like an unknown one. */
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        if (notifications.markRead(notificationId, userId, Instant.now(clock)) == 0
                && !notifications.existsByIdAndUserId(notificationId, userId)) {
            throw new ResourceNotFoundException("Notification");
        }
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return notifications.markAllRead(userId, Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public PreferencesResponse preferences(UUID userId) {
        NotificationPreferences settings = preferences.findById(userId).orElseGet(() -> NotificationPreferences.defaults(userId));
        return new PreferencesResponse(settings.isOrderUpdatesEmail(), settings.isSellerOrdersEmail());
    }

    /** Replaces both switches. Last write wins: two switches, no lost work worth a version check. */
    @Transactional
    public PreferencesResponse savePreferences(UUID userId, boolean orderUpdatesEmail, boolean sellerOrdersEmail) {
        NotificationPreferences settings = preferences.findById(userId).orElseGet(() -> NotificationPreferences.defaults(userId));
        settings.change(orderUpdatesEmail, sellerOrdersEmail);
        preferences.save(settings);
        return new PreferencesResponse(orderUpdatesEmail, sellerOrdersEmail);
    }
}
