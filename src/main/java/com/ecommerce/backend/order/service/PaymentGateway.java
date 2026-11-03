package com.ecommerce.backend.order.service;

import java.util.UUID;

/**
 * The payment provider, as the order module needs it. Card data never reaches this server: the
 * client exchanges the card for a one-time token with the provider (PCI DSS scope stays with the
 * provider) and sends only that token.
 *
 * <p>Implementations must be idempotent per {@code idempotencyKey}: a retried charge after a timeout
 * must not charge twice. Real providers (Stripe, Adyen) support exactly this.
 */
public interface PaymentGateway {

    /** The provider's answer. {@code reference} identifies the charge for refunds; null when declined. */
    record Charge(boolean approved, String reference) {
    }

    Charge charge(UUID idempotencyKey, long amountMinor, String currency, String paymentToken);

    /** Gives back part or all of a charge, for a cancelled order. */
    void refund(String chargeReference, long amountMinor, String currency);
}
