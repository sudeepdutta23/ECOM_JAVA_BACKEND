package com.ecommerce.backend.order.service;

import com.ecommerce.backend.order.OrderEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Business metrics from order events, counted after commit, so a rolled-back payment is never
 * counted. Tags stay low-cardinality: never an order, customer or seller ID.
 *
 * <ul>
 *   <li>{@code orders.paid}, {@code orders.shipped}, {@code orders.delivered}</li>
 *   <li>{@code orders.cancelled{by=customer|seller|system}}: {@code system} is the unpaid-order job</li>
 *   <li>{@code orders.revenue.minor{currency}}: paid amounts in minor units (cents)</li>
 * </ul>
 */
@Component
class OrderMetrics {

    private final MeterRegistry meters;

    OrderMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderEvent event) {
        switch (event) {
            case OrderEvent.Paid paid -> {
                meters.counter("orders.paid").increment();
                meters.counter("orders.revenue.minor", "currency", paid.currency()).increment(paid.totalMinor());
            }
            case OrderEvent.Shipped ignored -> meters.counter("orders.shipped").increment();
            case OrderEvent.Delivered ignored -> meters.counter("orders.delivered").increment();
            case OrderEvent.Cancelled cancelled -> meters.counter("orders.cancelled",
                    "by", cancelled.by().name().toLowerCase(Locale.ROOT)).increment();
        }
    }
}
