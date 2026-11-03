package com.ecommerce.backend.notification.service;

import com.ecommerce.backend.common.util.MinorUnits;
import com.ecommerce.backend.notification.NotificationType;
import com.ecommerce.backend.order.OrderEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What each order event says, and to whom: one place, so the inbox and the email never disagree.
 * Only IDs, amounts and the seller's public name go into the text; no addresses, no reasons.
 */
final class OrderMessages {

    /** Who a message is for, which decides the email preference that applies. */
    enum Audience { CUSTOMER, SELLER }

    /** @param email whether this message is also worth an email (subject to the recipient's preference) */
    record Message(UUID recipient, Audience audience, NotificationType type, String title, String body, String link,
                   boolean email) {
    }

    private OrderMessages() {
    }

    /**
     * @param customer the customer, or null if they cannot be reached (account erased or deleted)
     * @param sellerMembers everyone who works for the seller
     */
    static List<Message> render(OrderEvent event, UUID customer, List<UUID> sellerMembers) {
        String customerLink = "/orders/" + event.orderId();
        String sellerLink = "/organizations/" + event.organizationId() + "/orders/" + event.orderId();
        List<Message> messages = new ArrayList<>();
        switch (event) {
            case OrderEvent.Paid paid -> {
                String amount = MinorUnits.toAmount(paid.totalMinor()) + " " + paid.currency();
                if (customer != null) {
                    messages.add(new Message(customer, Audience.CUSTOMER, NotificationType.ORDER_CONFIRMED, "Order confirmed",
                            "Your order from " + paid.sellerName() + " (" + amount + ") is paid. The seller is preparing it.",
                            customerLink, true));
                }
                sellerMembers.forEach(member -> messages.add(new Message(member, Audience.SELLER, NotificationType.NEW_ORDER,
                        "New order to ship", "A customer paid an order of " + amount + ".", sellerLink, true)));
            }
            case OrderEvent.Shipped shipped -> {
                if (customer != null) {
                    String tracking = shipped.trackingNumber() == null ? "" : " Tracking number: " + shipped.trackingNumber() + ".";
                    messages.add(new Message(customer, Audience.CUSTOMER, NotificationType.ORDER_SHIPPED,
                            "Your order is on its way", shipped.sellerName() + " shipped your order." + tracking,
                            customerLink, true));
                }
            }
            case OrderEvent.Delivered delivered -> {
                if (customer != null) {
                    messages.add(new Message(customer, Audience.CUSTOMER, NotificationType.ORDER_DELIVERED,
                            "Your order was delivered", delivered.sellerName() + " marked your order as delivered.",
                            customerLink, false));
                }
            }
            case OrderEvent.Cancelled cancelled -> {
                if (cancelled.byCustomer()) {
                    sellerMembers.forEach(member -> messages.add(new Message(member, Audience.SELLER,
                            NotificationType.ORDER_CANCELLED_BY_CUSTOMER, "An order was cancelled",
                            "The customer cancelled an order before it shipped. Its units are back in stock.",
                            sellerLink, false)));
                } else if (customer != null) {
                    String refund = cancelled.refunded() ? " Your payment is being refunded." : "";
                    messages.add(new Message(customer, Audience.CUSTOMER, NotificationType.ORDER_CANCELLED_BY_SELLER,
                            "Your order was cancelled", cancelled.sellerName() + " cancelled your order. "
                            + "The reason is on the order page." + refund, customerLink, true));
                }
            }
        }
        return messages;
    }
}
