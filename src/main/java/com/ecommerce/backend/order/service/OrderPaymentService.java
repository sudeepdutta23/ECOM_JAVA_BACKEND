package com.ecommerce.backend.order.service;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.order.OrderStatus;
import com.ecommerce.backend.order.dto.CheckoutResponse;
import com.ecommerce.backend.order.entity.Checkout;
import com.ecommerce.backend.order.entity.SalesOrder;
import com.ecommerce.backend.order.repository.CheckoutRepository;
import com.ecommerce.backend.order.repository.SalesOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paying for a checkout: one charge for all of its orders still waiting for payment.
 *
 * <p>The checkout row is locked first, so two "pay" requests cannot both charge; the charge carries
 * the checkout ID as its idempotency key, so a retry after a timeout cannot charge twice either.
 *
 * <p>The simulated provider answers at once. A real one is called outside the transaction, with a
 * webhook confirming the charge (Phase 12/13): holding database locks during a network call to a
 * third party would let a slow provider block checkouts.
 */
@Service
public class OrderPaymentService {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentService.class);

    private final CheckoutRepository checkouts;
    private final SalesOrderRepository orders;
    private final PaymentGateway gateway;
    private final Clock clock;

    public OrderPaymentService(CheckoutRepository checkouts, SalesOrderRepository orders, PaymentGateway gateway,
                               Clock clock) {
        this.checkouts = checkouts;
        this.orders = orders;
        this.gateway = gateway;
        this.clock = clock;
    }

    /** Idempotent: paying a paid checkout returns it unchanged. A declined payment changes nothing (402). */
    @Transactional
    public CheckoutResponse pay(UUID userId, UUID checkoutId, String paymentToken) {
        Checkout checkout = checkouts.lockForPayment(checkoutId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Checkout"));
        if (checkout.isPaid()) {
            return response(checkout);
        }
        List<SalesOrder> pending = orders.lockAllOfCheckout(checkoutId).stream()
                .filter(order -> order.getStatus() == OrderStatus.PENDING_PAYMENT).toList();
        if (pending.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATUS, "Every order of this checkout was cancelled.");
        }
        long amount = pending.stream().mapToLong(SalesOrder::getTotalMinor).reduce(0, Math::addExact);

        PaymentGateway.Charge charge = gateway.charge(checkoutId, amount, checkout.getCurrency(), paymentToken);
        if (!charge.approved()) {
            log.info("Payment declined: checkoutId={}, userId={}", checkoutId, userId);
            throw new BusinessException(ErrorCode.ORDER_PAYMENT_DECLINED,
                    "Your payment was declined. Nothing was charged; try another payment method.");
        }
        Instant now = Instant.now(clock);
        checkout.recordPayment(charge.reference(), now);
        pending.forEach(order -> order.markPaid(now));
        orders.flush();
        log.info("Checkout paid: checkoutId={}, userId={}, orders={}, amountMinor={}", checkoutId, userId, pending.size(), amount);
        return response(checkout);
    }

    private CheckoutResponse response(Checkout checkout) {
        return CheckoutResponse.from(checkout, orders.findByCheckoutIdOrderByIdAsc(checkout.getId()));
    }
}
