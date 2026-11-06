package com.ecommerce.backend.order.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands in for a real provider until one is integrated: approves every token except
 * {@code tok_decline}, like the test tokens real providers offer. Selected with
 * {@code app.payment.provider=simulated}; production configuration must name a real provider, so
 * without one the application has no {@link PaymentGateway} and does not start.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "simulated")
class SimulatedPaymentGateway implements PaymentGateway {

    static final String DECLINING_TOKEN = "tok_decline";

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    /** Impossible to miss in any environment's logs: with this gateway, nothing is really charged. */
    SimulatedPaymentGateway() {
        log.warn("SIMULATED PAYMENTS: orders are not really charged. Never use app.payment.provider=simulated with real customers");
    }

    @Override
    public Charge charge(UUID idempotencyKey, long amountMinor, String currency, String paymentToken) {
        if (DECLINING_TOKEN.equals(paymentToken)) {
            log.info("Simulated payment declined: key={}", idempotencyKey);
            return new Charge(false, null);
        }
        // Deterministic per key, like a real provider's idempotent charge.
        String reference = "sim_" + idempotencyKey;
        log.info("Simulated payment approved: key={}, reference={}, amountMinor={}", idempotencyKey, reference, amountMinor);
        return new Charge(true, reference);
    }

    @Override
    public void refund(String chargeReference, long amountMinor, String currency) {
        log.info("Simulated refund: reference={}, amountMinor={}", chargeReference, amountMinor);
    }
}
