package com.ecommerce.backend.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body of {@code POST /api/v1/checkouts/{checkoutId}/pay}. Never a card number: a provider token. */
@Schema(description = "Pay for a checkout")
public record PaymentRequest(
        @Schema(example = "tok_visa", description = "One-time token from the payment provider's client SDK. "
                + "The simulator declines tok_decline")
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,200}$", message = "must be a payment provider token")
        String paymentToken) {

    /** A token is a credential for one charge: never log it. */
    @Override
    public String toString() {
        return "PaymentRequest[...]";
    }
}
