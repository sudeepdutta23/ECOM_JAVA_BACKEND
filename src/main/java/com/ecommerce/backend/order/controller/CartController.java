package com.ecommerce.backend.order.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.order.dto.CartQuantityRequest;
import com.ecommerce.backend.order.dto.CartResponse;
import com.ecommerce.backend.order.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's cart; the account always comes from the token. */
@RestController
@RequestMapping(path = "/api/v1/cart", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cart")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class CartController {

    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping
    @Operation(summary = "My cart at current prices",
            description = "Lines that cannot be bought now are marked (NOT_ON_SALE, NOT_ENOUGH_STOCK) and left out of the total.")
    public ApiResponse<CartResponse> view(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("Cart", cart.view(callerId(jwt)));
    }

    @PutMapping(path = "/items/{productId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Set how many of a product are in my cart",
            description = "Adds the line if new. 404 if the product is not on sale; 409 ORDER_CART_FULL at the line limit.")
    public ApiResponse<CartResponse> setQuantity(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                                                 @Valid @RequestBody CartQuantityRequest request) {
        return ApiResponse.success("Cart updated", cart.setQuantity(callerId(jwt), productId, request.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove a product from my cart", description = "Idempotent.")
    public ApiResponse<CartResponse> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return ApiResponse.success("Cart updated", cart.remove(callerId(jwt), productId));
    }

    @DeleteMapping
    @Operation(summary = "Empty my cart")
    public ResponseEntity<Void> clear(@AuthenticationPrincipal Jwt jwt) {
        cart.clear(callerId(jwt));
        return ResponseEntity.noContent().build();
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
