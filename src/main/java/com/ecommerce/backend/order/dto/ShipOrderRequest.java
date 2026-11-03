package com.ecommerce.backend.order.dto;

import com.ecommerce.backend.common.validation.Inputs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST .../orders/{orderId}/ship}. */
@Schema(description = "Mark an order as shipped")
public record ShipOrderRequest(
        @Schema(example = "1Z999AA10123456784", description = "Optional; shown to the customer")
        @Size(max = 100) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String trackingNumber) {

    public ShipOrderRequest {
        trackingNumber = Inputs.text(trackingNumber);
    }
}
