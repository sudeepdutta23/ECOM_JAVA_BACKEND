package com.ecommerce.backend.catalog.controller;

import com.ecommerce.backend.catalog.dto.CategoryResponse;
import com.ecommerce.backend.catalog.dto.StorefrontProductResponse;
import com.ecommerce.backend.catalog.service.CategoryService;
import com.ecommerce.backend.catalog.service.StorefrontService;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The public storefront: no sign-in needed (SecurityConfig permits GET /api/v1/catalog/**). */
@RestController
@RequestMapping(path = "/api/v1/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Storefront")
public class StorefrontController {

    private final StorefrontService storefront;
    private final CategoryService categories;

    public StorefrontController(StorefrontService storefront, CategoryService categories) {
        this.storefront = storefront;
        this.categories = categories;
    }

    @GetMapping("/products")
    @Operation(summary = "Products on sale, newest first", description = "Only approved, active sellers.")
    public ApiResponse<PageResponse<StorefrontProductResponse>> products(
            @Parameter(description = "Category slug") @RequestParam(required = false) @Size(max = 50) String category,
            @Parameter(description = "Seller slug") @RequestParam(required = false) @Size(max = 50) String seller,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return ApiResponse.success("Products", storefront.list(category, seller, page, size));
    }

    @GetMapping("/products/{productId}")
    @Operation(summary = "One product on sale", description = "404 for anything not on sale, like an unknown ID.")
    public ApiResponse<StorefrontProductResponse> product(@PathVariable UUID productId) {
        return ApiResponse.success("Product", storefront.get(productId));
    }

    @GetMapping("/categories")
    @Operation(summary = "All categories, by name")
    public ApiResponse<List<CategoryResponse>> categories() {
        return ApiResponse.success("Categories", categories.list());
    }
}
