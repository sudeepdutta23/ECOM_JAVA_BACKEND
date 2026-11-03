package com.ecommerce.backend.order.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.order.dto.CancelOrderRequest;
import com.ecommerce.backend.order.dto.OrderResponse;
import com.ecommerce.backend.order.dto.OrderSummary;
import com.ecommerce.backend.order.service.CustomerOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's orders. */
@RestController
@RequestMapping(path = "/api/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Orders")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class OrderController {

    private final CustomerOrderService orders;

    public OrderController(CustomerOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @Operation(summary = "My orders, newest first")
    public ApiResponse<PageResponse<OrderSummary>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return ApiResponse.success("Orders", orders.list(callerId(jwt), page, size));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "One of my orders, with lines and addresses", description = "404 if it is not yours.")
    public ApiResponse<OrderResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return ApiResponse.success("Order", orders.get(callerId(jwt), orderId));
    }

    @PostMapping(path = "/{orderId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel one of my orders before it ships",
            description = "The stock goes back and a paid order is refunded. 409 ORDER_INVALID_STATUS once shipped.")
    public ApiResponse<OrderResponse> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
                                             @Valid @RequestBody CancelOrderRequest request) {
        return ApiResponse.success("Order cancelled", orders.cancel(callerId(jwt), orderId, request.reason()));
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
