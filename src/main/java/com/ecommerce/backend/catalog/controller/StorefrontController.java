package com.ecommerce.backend.catalog.controller;

import com.ecommerce.backend.catalog.StorefrontSort;
import com.ecommerce.backend.catalog.dto.CategoryResponse;
import com.ecommerce.backend.catalog.dto.StorefrontProductResponse;
import com.ecommerce.backend.catalog.service.CategoryService;
import com.ecommerce.backend.catalog.service.ProductImageService;
import com.ecommerce.backend.catalog.service.StorefrontQuery;
import com.ecommerce.backend.catalog.service.StorefrontService;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.common.web.HttpCaching;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The public storefront: no sign-in needed (SecurityConfig permits GET /api/v1/catalog/**).
 *
 * <p>Responses are the same for every visitor, so browsers and CDNs may cache them ({@link HttpCaching}):
 * briefly, because a suspended seller or a price change must show soon. Products for 30 s, like their
 * server-side cache, so a change is visible everywhere within about a minute; categories, which
 * change rarely, for 5 minutes.
 */
@RestController
@RequestMapping(path = "/api/v1/catalog")
@Tag(name = "Storefront")
public class StorefrontController {

    private final StorefrontService storefront;
    private final CategoryService categories;
    private final ProductImageService images;
    private final ObjectMapper mapper;

    static final Duration PRODUCTS_MAX_AGE = Duration.ofSeconds(30);
    static final Duration CATEGORIES_MAX_AGE = Duration.ofMinutes(5);

    public StorefrontController(StorefrontService storefront, CategoryService categories, ProductImageService images,
                                ObjectMapper mapper) {
        this.storefront = storefront;
        this.categories = categories;
        this.images = images;
        this.mapper = mapper;
    }

    @GetMapping(path = "/products", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Search the products on sale",
            description = "Only approved, active sellers. Every filter is optional. q is searched in names and "
                    + "descriptions (\"two words\" for a phrase, -word to exclude). Sort defaults to RELEVANCE with q, "
                    + "NEWEST without. At most 10 000 results deep.")
    public ResponseEntity<ApiResponse<PageResponse<StorefrontProductResponse>>> products(
            @Parameter(description = "Search text") @RequestParam(required = false) @Size(max = 100) String q,
            @Parameter(description = "Category slug") @RequestParam(required = false) @Size(max = 50) String category,
            @Parameter(description = "Seller slug") @RequestParam(required = false) @Size(max = 50) String seller,
            @Parameter(description = "Lowest price, inclusive") @RequestParam(required = false)
            @DecimalMin("0") @Digits(integer = 7, fraction = 2) BigDecimal minPrice,
            @Parameter(description = "Highest price, inclusive") @RequestParam(required = false)
            @DecimalMin("0") @Digits(integer = 7, fraction = 2) BigDecimal maxPrice,
            @Parameter(description = "Only products that can be bought now") @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(required = false) StorefrontSort sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size,
            WebRequest request) {
        return HttpCaching.publicJson(request, mapper, PRODUCTS_MAX_AGE, "Products", storefront.list(new StorefrontQuery(q,
                category, seller, minPrice, maxPrice, inStock, sort, page, size)));
    }

    @GetMapping(path = "/products/{productId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "One product on sale", description = "404 for anything not on sale, like an unknown ID.")
    public ResponseEntity<ApiResponse<StorefrontProductResponse>> product(@PathVariable UUID productId, WebRequest request) {
        return HttpCaching.publicJson(request, mapper, PRODUCTS_MAX_AGE, "Product", storefront.get(productId));
    }

    @GetMapping("/products/{productId}/images/{imageId}")
    @Operation(summary = "An image of a product on sale",
            description = "Cached publicly for a day. 404 when the product is not on sale, like an unknown ID.")
    public ResponseEntity<byte[]> image(@PathVariable UUID productId, @PathVariable UUID imageId) {
        ProductImageService.Content content = images.publicContent(productId, imageId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic()).body(content.bytes());
    }

    @GetMapping(path = "/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "All categories, by name")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> categories(WebRequest request) {
        return HttpCaching.publicJson(request, mapper, CATEGORIES_MAX_AGE, "Categories", categories.list());
    }
}
