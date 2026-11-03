package com.ecommerce.backend.order.service;

import com.ecommerce.backend.catalog.service.ProductCatalog;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.order.OrderStatus;
import com.ecommerce.backend.order.entity.Checkout;
import com.ecommerce.backend.order.entity.OrderLine;
import com.ecommerce.backend.order.entity.SalesOrder;
import com.ecommerce.backend.order.repository.CheckoutRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancelling one order, the same way whoever asks (customer or seller): the stock goes back to the
 * catalogue, a paid order is refunded, all in the caller's transaction.
 */
@Component
class OrderCancellation {

    private final ProductCatalog catalog;
    private final CheckoutRepository checkouts;
    private final PaymentGateway gateway;
    private final Clock clock;

    OrderCancellation(ProductCatalog catalog, CheckoutRepository checkouts, PaymentGateway gateway, Clock clock) {
        this.catalog = catalog;
        this.checkouts = checkouts;
        this.gateway = gateway;
        this.clock = clock;
    }

    /** @param order locked by the caller, so a parallel shipment or cancellation waits */
    @Transactional(propagation = Propagation.MANDATORY)
    void cancel(SalesOrder order, UUID by, String reason) {
        if (!order.getStatus().isCancellable()) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATUS, "This order is " + order.getStatus()
                    + " and can no longer be cancelled.");
        }
        boolean wasPaid = order.getStatus() == OrderStatus.PAID;
        order.cancel(by, reason, Instant.now(clock));
        // Product ID order, like checkout's reservation: the same lock order everywhere.
        order.getLines().stream().sorted(Comparator.comparing(OrderLine::getProductId))
                .forEach(line -> catalog.release(line.getProductId(), line.getQuantity()));
        if (wasPaid) {
            Checkout checkout = checkouts.findById(order.getCheckoutId()).orElseThrow();
            gateway.refund(checkout.getPaymentReference(), order.getTotalMinor(), order.getCurrency());
        }
    }
}
