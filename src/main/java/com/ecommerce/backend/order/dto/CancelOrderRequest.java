package com.ecommerce.backend.order.dto;

import com.ecommerce.backend.common.validation.Inputs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of the cancel commands. The reason is shown to the other side (customer or seller), so it is
 * stored but never logged. Sellers must give one; customers may.
 */
@Schema(description = "Cancel an order")
public record CancelOrderRequest(
        @Schema(example = "Out of stock at the warehouse")
        @Size(max = 500) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String reason) {

    public CancelOrderRequest {
        reason = Inputs.text(reason);
    }

    @Override
    public String toString() {
        return "CancelOrderRequest[...]";
    }
}
