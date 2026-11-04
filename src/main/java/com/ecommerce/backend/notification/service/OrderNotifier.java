package com.ecommerce.backend.notification.service;

import com.ecommerce.backend.common.mail.EmailMessage;
import com.ecommerce.backend.common.mail.EmailOutbox;
import com.ecommerce.backend.notification.entity.Notification;
import com.ecommerce.backend.notification.entity.NotificationPreferences;
import com.ecommerce.backend.notification.repository.NotificationPreferencesRepository;
import com.ecommerce.backend.notification.repository.NotificationRepository;
import com.ecommerce.backend.order.OrderEvent;
import com.ecommerce.backend.org.service.SellerDirectory;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns order events into notifications, in the order's transaction ({@code @EventListener}):
 * the inbox entries and the emails (written to the {@link EmailOutbox}) commit with the order
 * change, or nothing does. A notification about a rolled-back payment cannot exist, and none can be
 * lost. The emails are sent after commit, so SMTP latency never slows a checkout.
 */
@Component
class OrderNotifier {

    private final NotificationRepository notifications;
    private final NotificationPreferencesRepository preferences;
    private final SellerDirectory sellers;
    private final UserDirectory users;
    private final EmailOutbox outbox;
    private final NotificationProperties properties;

    OrderNotifier(NotificationRepository notifications, NotificationPreferencesRepository preferences,
                  SellerDirectory sellers, UserDirectory users, EmailOutbox outbox, NotificationProperties properties) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.sellers = sellers;
        this.users = users;
        this.outbox = outbox;
        this.properties = properties;
    }

    @EventListener
    public void on(OrderEvent event) {
        List<OrderMessages.Message> messages = OrderMessages.render(event, reachableCustomer(event),
                sellers.memberIds(event.organizationId()));
        notifications.saveAll(messages.stream()
                .map(m -> Notification.of(m.recipient(), m.type(), m.title(), m.body(), m.link())).toList());
        email(messages);
    }

    private void email(List<OrderMessages.Message> messages) {
        List<OrderMessages.Message> wanted = messages.stream()
                .filter(OrderMessages.Message::email)
                .filter(m -> users.isActive(m.recipient()) && wants(m))
                .toList();
        Map<UUID, String> emails = users.emailsOf(wanted.stream().map(OrderMessages.Message::recipient).toList());
        for (OrderMessages.Message message : wanted) {
            String to = emails.get(message.recipient());
            if (to == null) {
                continue;
            }
            // Fixed subjects: the seller's name is in the body only, so no seller can dress up an email's subject.
            String body = message.body() + "\n\nSee it here: " + properties.frontendBaseUrl() + message.link()
                    + "\n\nYou can switch these emails off in your notification settings.";
            outbox.enqueue(new EmailMessage(to, message.title(), body));
        }
    }

    /** The customer, unless their account is gone: an erased person gets no new notifications. */
    private UUID reachableCustomer(OrderEvent event) {
        UUID customer = event.customerId();
        return customer != null && users.exists(customer) ? customer : null;
    }

    private boolean wants(OrderMessages.Message message) {
        NotificationPreferences settings = preferences.findById(message.recipient())
                .orElseGet(() -> NotificationPreferences.defaults(message.recipient()));
        return message.audience() == OrderMessages.Audience.CUSTOMER ? settings.isOrderUpdatesEmail()
                : settings.isSellerOrdersEmail();
    }
}
