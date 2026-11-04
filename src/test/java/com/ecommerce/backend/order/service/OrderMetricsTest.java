package com.ecommerce.backend.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.order.OrderEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OrderMetrics metrics = new OrderMetrics(meters);
    private final UUID order = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();

    @Test
    void countsEachTransitionAndTheRevenueWithoutIds() {
        metrics.on(new OrderEvent.Paid(order, seller, customer, "Shop", 2_499, "EUR"));
        metrics.on(new OrderEvent.Paid(order, seller, customer, "Shop", 1_001, "EUR"));
        metrics.on(new OrderEvent.Shipped(order, seller, customer, "Shop", "TRACK"));
        metrics.on(new OrderEvent.Delivered(order, seller, customer, "Shop"));
        metrics.on(new OrderEvent.Cancelled(order, seller, customer, "Shop", OrderEvent.Canceller.SYSTEM, false));

        assertThat(meters.get("orders.paid").counter().count()).isEqualTo(2);
        assertThat(meters.get("orders.revenue.minor").tag("currency", "EUR").counter().count()).isEqualTo(3_500);
        assertThat(meters.get("orders.shipped").counter().count()).isEqualTo(1);
        assertThat(meters.get("orders.delivered").counter().count()).isEqualTo(1);
        assertThat(meters.get("orders.cancelled").tag("by", "system").counter().count()).isEqualTo(1);
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .noneMatch(tag -> tag.getValue().equals(order.toString()) || tag.getValue().equals(seller.toString())));
    }
}
