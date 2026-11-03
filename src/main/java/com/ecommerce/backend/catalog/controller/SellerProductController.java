package com.ecommerce.backend.catalog.controller;

import com.ecommerce.backend.catalog.ProductStatus;
import com.ecommerce.backend.catalog.dto.ProductRequest;
import com.ecommerce.backend.catalog.dto.ProductResponse;
import com.ecommerce.backend.catalog.dto.StockAdjustmentRequest;
import com.ecommerce.backend.catalog.service.ProductService;
import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A seller's catalogue. Authorised by the caller's organisation role (CATALOG_MANAGE for changes),
 * checked per request; non-members get 404 for every {@code {orgId}}.
 */
@RestController
@RequestMapping(path = "/api/v1/organizations/{orgId}/products", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Seller catalogue")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SellerProductController {

    private final ProductService products;

    public SellerProductController(ProductService products) {
        this.products = products;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a product (starts as DRAFT, without stock)",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created; Location header"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "CATALOG_SKU_TAKEN or ORG_INVALID_STATUS (suspended or closed)")})
    public ResponseEntity<ApiResponse<ProductResponse>> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                                               @Valid @RequestBody ProductRequest request) {
        ProductResponse created = products.create(callerId(jwt), orgId, request);
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + orgId + "/products/" + created.id()))
                .body(ApiResponse.success("Product created", created));
    }

    @GetMapping
    @Operation(summary = "The shop's products, newest first")
    public ApiResponse<PageResponse<ProductResponse>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
            @Parameter(description = "Only this status") @RequestParam(required = false) ProductStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return ApiResponse.success("Products", products.list(callerId(jwt), orgId, status, page, size));
    }

    @GetMapping("/{productId}")
    @Operation(summary = "One product of the shop, with stock")
    public ApiResponse<ProductResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                            @PathVariable UUID productId) {
        return ApiResponse.success("Product", products.get(callerId(jwt), orgId, productId));
    }

    @PutMapping(path = "/{productId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Replace name, SKU, description, price and category",
            description = "Send the version you read. Orders already placed keep the old name and price.")
    public ApiResponse<ProductResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                               @PathVariable UUID productId, @Valid @RequestBody ProductRequest request) {
        return ApiResponse.success("Product updated", products.update(callerId(jwt), orgId, productId, request));
    }

    @PostMapping("/{productId}/publish")
    @Operation(summary = "Put the product on sale",
            description = "From DRAFT or ARCHIVED. Customers see it once the shop is approved and not suspended.")
    public ApiResponse<ProductResponse> publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                                @PathVariable UUID productId) {
        return ApiResponse.success("Product published", products.publish(callerId(jwt), orgId, productId));
    }

    @PostMapping("/{productId}/archive")
    @Operation(summary = "Take the product off sale; it stays for order history")
    public ApiResponse<ProductResponse> archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                                @PathVariable UUID productId) {
        return ApiResponse.success("Product archived", products.archive(callerId(jwt), orgId, productId));
    }

    @PostMapping(path = "/{productId}/stock", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Add or remove stock",
            description = "A change, not a total: {\"delta\": 25} books in a delivery. 409 CATALOG_STOCK_INSUFFICIENT "
                    + "if stock would drop below zero.")
    public ApiResponse<ProductResponse> adjustStock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                                    @PathVariable UUID productId,
                                                    @Valid @RequestBody StockAdjustmentRequest request) {
        return ApiResponse.success("Stock adjusted",
                products.adjustStock(callerId(jwt), orgId, productId, request.delta()));
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
