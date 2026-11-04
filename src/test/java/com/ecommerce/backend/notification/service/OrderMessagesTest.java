package com.ecommerce.backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.notification.NotificationType;
import com.ecommerce.backend.order.OrderEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderMessagesTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final List<UUID> STAFF = List.of(UUID.randomUUID(), UUID.randomUUID());

    @Test
    void aPaidOrderConfirmsToTheCustomerAndTellsEverySellerMember() {
        List<OrderMessages.Message> messages = OrderMessages.render(
                new OrderEvent.Paid(ORDER, SELLER, CUSTOMER, "Acme", 3998, "EUR"), CUSTOMER, STAFF);

        assertThat(messages).extracting(OrderMessages.Message::type).containsExactly(NotificationType.ORDER_CONFIRMED,
                NotificationType.NEW_ORDER, NotificationType.NEW_ORDER);
        assertThat(messages.get(0).body()).contains("Acme").contains("39.98 EUR");
        assertThat(messages.get(0).link()).isEqualTo("/orders/" + ORDER);
        assertThat(messages.get(1).link()).isEqualTo("/organizations/" + SELLER + "/orders/" + ORDER);
        assertThat(messages).allMatch(OrderMessages.Message::email);
    }

    @Test
    void cancellationsTellTheOtherSide() {
        List<OrderMessages.Message> bySeller = OrderMessages.render(
                new OrderEvent.Cancelled(ORDER, SELLER, CUSTOMER, "Acme", OrderEvent.Canceller.SELLER, true), CUSTOMER, STAFF);
        List<OrderMessages.Message> byCustomer = OrderMessages.render(
                new OrderEvent.Cancelled(ORDER, SELLER, CUSTOMER, "Acme", OrderEvent.Canceller.CUSTOMER, false), CUSTOMER, STAFF);

        assertThat(bySeller).singleElement().satisfies(m -> {
            assertThat(m.recipient()).isEqualTo(CUSTOMER);
            assertThat(m.body()).contains("refunded");
        });
        assertThat(byCustomer).extracting(OrderMessages.Message::recipient).containsExactlyElementsOf(STAFF);
        assertThat(byCustomer).noneMatch(OrderMessages.Message::email);
    }

    @Test
    void anUnreachableCustomerGetsNothingButTheSellerStillDoes() {
        List<OrderMessages.Message> messages = OrderMessages.render(
                new OrderEvent.Paid(ORDER, SELLER, CUSTOMER, "Acme", 100, "EUR"), null, STAFF);

        assertThat(messages).extracting(OrderMessages.Message::audience).containsOnly(OrderMessages.Audience.SELLER);
    }

    @Test
    void trackingNumbersAppearOnlyWhenGivenAndDeliveriesAreInboxOnly() {
        assertThat(OrderMessages.render(new OrderEvent.Shipped(ORDER, SELLER, CUSTOMER, "Acme", "1Z99"), CUSTOMER, STAFF)
                .get(0).body()).contains("Tracking number: 1Z99");
        assertThat(OrderMessages.render(new OrderEvent.Shipped(ORDER, SELLER, CUSTOMER, "Acme", null), CUSTOMER, STAFF)
                .get(0).body()).doesNotContain("Tracking");
        assertThat(OrderMessages.render(new OrderEvent.Delivered(ORDER, SELLER, CUSTOMER, "Acme"), CUSTOMER, STAFF))
                .singleElement().satisfies(m -> assertThat(m.email()).isFalse());
    }

    @Test
    void anOrderThePlatformCancelledForNonPaymentTellsOnlyTheCustomer() {
        List<OrderMessages.Message> messages = OrderMessages.render(
                new OrderEvent.Cancelled(ORDER, SELLER, CUSTOMER, "Acme", OrderEvent.Canceller.SYSTEM, false), CUSTOMER, STAFF);

        assertThat(messages).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo(NotificationType.ORDER_EXPIRED);
            assertThat(m.body()).contains("not paid in time");
        });
    }
}
