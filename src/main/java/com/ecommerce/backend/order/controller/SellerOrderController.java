package com.ecommerce.backend.order.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.order.OrderStatus;
import com.ecommerce.backend.order.dto.CancelOrderRequest;
import com.ecommerce.backend.order.dto.OrderResponse;
import com.ecommerce.backend.order.dto.OrderSummary;
import com.ecommerce.backend.order.dto.ShipOrderRequest;
import com.ecommerce.backend.order.service.SellerOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

/** A seller's orders. Authorised by the caller's organisation role; non-members get 404. */
@RestController
@RequestMapping(path = "/api/v1/organizations/{orgId}/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Seller orders")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SellerOrderController {

    private final SellerOrderService orders;

    public SellerOrderController(SellerOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @Operation(summary = "The shop's orders, newest first")
    public ApiResponse<PageResponse<OrderSummary>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
            @Parameter(description = "Only this status, e.g. PAID: the orders to ship") @RequestParam(required = false)
            OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return ApiResponse.success("Orders", orders.list(callerId(jwt), orgId, status, page, size));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "One order of the shop, with the shipping address")
    public ApiResponse<OrderResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                          @PathVariable UUID orderId) {
        return ApiResponse.success("Order", orders.get(callerId(jwt), orgId, orderId));
    }

    @PostMapping(path = "/{orderId}/ship", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Mark a paid order as shipped", description = "Needs ORDER_MANAGE. Works while suspended.")
    public ApiResponse<OrderResponse> ship(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                           @PathVariable UUID orderId, @Valid @RequestBody ShipOrderRequest request) {
        return ApiResponse.success("Order shipped", orders.ship(callerId(jwt), orgId, orderId, request.trackingNumber()));
    }

    @PostMapping("/{orderId}/deliver")
    @Operation(summary = "Mark a shipped order as delivered")
    public ApiResponse<OrderResponse> deliver(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                              @PathVariable UUID orderId) {
        return ApiResponse.success("Order delivered", orders.markDelivered(callerId(jwt), orgId, orderId));
    }

    @PostMapping(path = "/{orderId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel an order before it ships",
            description = "A reason is required; the customer sees it. Stock goes back, a paid order is refunded.")
    public ApiResponse<OrderResponse> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                             @PathVariable UUID orderId, @Valid @RequestBody CancelOrderRequest request) {
        return ApiResponse.success("Order cancelled", orders.cancel(callerId(jwt), orgId, orderId, request.reason()));
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
