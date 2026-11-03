package com.ecommerce.backend.order.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.order.dto.CheckoutRequest;
import com.ecommerce.backend.order.dto.CheckoutResponse;
import com.ecommerce.backend.order.dto.PaymentRequest;
import com.ecommerce.backend.order.service.CheckoutService;
import com.ecommerce.backend.order.service.OrderPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Checking out the signed-in customer's cart. */
@RestController
@Tag(name = "Checkout")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class CheckoutController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final CheckoutService checkouts;
    private final OrderPaymentService payments;

    public CheckoutController(CheckoutService checkouts, OrderPaymentService payments) {
        this.checkouts = checkouts;
        this.payments = payments;
    }

    @PostMapping(path = "/api/v1/checkout", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Buy what is in my cart: one order per seller",
            description = "Send a new Idempotency-Key for every purchase; a retry with the same key returns the same "
                    + "checkout (200, Idempotent-Replayed: true) instead of buying twice. Orders start PENDING_PAYMENT.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Placed"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Replayed"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "VALIDATION_FAILED: no Idempotency-Key, not your address"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "ORDER_CART_NOT_READY, ORDER_TOTAL_CHANGED, ORDER_OUT_OF_STOCK, "
                                    + "ORDER_SELLER_NOT_SELLING: nothing was bought")})
    public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "New for every purchase; a UUID works", example = "6f1c2a1e-0d4b-4c7e-9b1a-3e2f5d6c7b8a")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        CheckoutService.Result result = checkouts.checkout(callerId(jwt), idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(ApiResponse.success("Checkout", result.checkout()));
        }
        return ResponseEntity.created(URI.create("/api/v1/checkouts/" + result.checkout().id()))
                .body(ApiResponse.success("Orders placed", result.checkout()));
    }

    @GetMapping(path = "/api/v1/checkouts/{checkoutId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "One of my checkouts with its orders", description = "404 if it is not yours.")
    public ApiResponse<CheckoutResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID checkoutId) {
        return ApiResponse.success("Checkout", checkouts.get(callerId(jwt), checkoutId));
    }

    @PostMapping(path = "/api/v1/checkouts/{checkoutId}/pay", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Pay for a checkout: one charge for all its orders",
            description = "Send the payment provider's token, never card data. Idempotent: paying a paid checkout "
                    + "returns it. 402 ORDER_PAYMENT_DECLINED changes nothing.")
    public ApiResponse<CheckoutResponse> pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID checkoutId,
                                             @Valid @RequestBody PaymentRequest request) {
        return ApiResponse.success("Checkout paid", payments.pay(callerId(jwt), checkoutId, request.paymentToken()));
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
