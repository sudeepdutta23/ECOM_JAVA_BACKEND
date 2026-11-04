package com.ecommerce.backend.order.service;

import com.ecommerce.backend.order.OrderEvent;
import com.ecommerce.backend.order.OrderStatus;
import com.ecommerce.backend.order.entity.SalesOrder;
import com.ecommerce.backend.order.repository.SalesOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Cancels orders not paid within {@code app.order.payment-timeout} and returns their stock, so an
 * abandoned checkout cannot hold units forever (a Phase 6 accepted risk).
 *
 * <p>One short transaction per order, never one for the whole batch: each order is locked, checked
 * again and cancelled on its own. A customer paying at that very moment either wins the lock (the
 * order is then PAID and skipped) or waits and finds it cancelled. One order per transaction also
 * matters because returning stock clears the persistence context.
 */
@Component
public class UnpaidOrderExpiry {

    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderExpiry.class);

    /** Orders per round; the next round (one minute later) takes the rest. */
    static final int BATCH = 200;

    private final SalesOrderRepository orders;
    private final OrderCancellation cancellation;
    private final OrderProperties properties;
    private final TransactionOperations transactions;
    private final Clock clock;

    public UnpaidOrderExpiry(SalesOrderRepository orders, OrderCancellation cancellation, OrderProperties properties,
                             TransactionOperations transactions, Clock clock) {
        this.orders = orders;
        this.cancellation = cancellation;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.unpaid-orders-interval:PT1M}", initialDelayString = "PT30S")
    @SchedulerLock(name = "unpaid-order-expiry", lockAtMostFor = "PT5M")
    public void run() {
        int expired = expireDue();
        if (expired > 0) {
            log.info("Unpaid orders expired: count={}", expired);
        }
    }

    /** @return how many orders were cancelled in this round */
    public int expireDue() {
        Instant cutoff = Instant.now(clock).minus(properties.paymentTimeout());
        List<UUID> due = orders.findUnpaidCreatedBefore(cutoff, PageRequest.of(0, BATCH));
        int expired = 0;
        for (UUID orderId : due) {
            Boolean cancelled = transactions.execute(status -> expire(orderId, cutoff));
            expired += Boolean.TRUE.equals(cancelled) ? 1 : 0;
        }
        return expired;
    }

    private boolean expire(UUID orderId, Instant cutoff) {
        SalesOrder order = orders.lockById(orderId).orElse(null);
        // Checked again under the lock: it may have been paid or cancelled since it was listed.
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT || !order.getCreatedAt().isBefore(cutoff)) {
            return false;
        }
        cancellation.cancel(order, null, "Not paid within " + properties.paymentTimeout().toMinutes() + " minutes",
                OrderEvent.Canceller.SYSTEM);
        return true;
    }
}
