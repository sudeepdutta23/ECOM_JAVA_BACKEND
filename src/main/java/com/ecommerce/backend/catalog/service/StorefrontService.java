package com.ecommerce.backend.catalog.service;

import com.ecommerce.backend.catalog.dto.StorefrontProductResponse;
import com.ecommerce.backend.catalog.entity.Category;
import com.ecommerce.backend.catalog.entity.Product;
import com.ecommerce.backend.catalog.entity.ProductImage;
import com.ecommerce.backend.catalog.repository.CategoryRepository;
import com.ecommerce.backend.catalog.repository.ProductImageRepository;
import com.ecommerce.backend.catalog.repository.ProductRepository;
import com.ecommerce.backend.catalog.repository.ProductSearchRepository;
import com.ecommerce.backend.common.cache.JsonCache;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.common.util.MinorUnits;
import com.ecommerce.backend.org.SellerInfo;
import com.ecommerce.backend.org.service.SellerDirectory;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public storefront: ACTIVE products of ACTIVE sellers, nothing else. A draft, an archived
 * product and anything of a pending or suspended seller answer 404, like an ID that never existed.
 *
 * <p>A page costs six queries whatever its size: the matching IDs and their count, the products,
 * their categories, their sellers and their images.
 * Search, filters and sorting: docs/modules/SEARCH.md (Phase 10).
 */
@Service
public class StorefrontService {

    /** How deep the storefront pages: page x size. */
    static final int MAX_DEPTH = 10_000;

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final SellerDirectory sellers;
    private final ProductImageRepository images;
    private final ProductSearchRepository search;
    private final JsonCache cache;

    static final long PRODUCT_TTL_SECONDS = 30;
    private static final TypeReference<StorefrontProductResponse> PRODUCT = new TypeReference<>() {
    };

    public StorefrontService(ProductRepository products, CategoryRepository categories, SellerDirectory sellers,
                             ProductImageRepository images, ProductSearchRepository search, JsonCache cache) {
        this.products = products;
        this.categories = categories;
        this.sellers = sellers;
        this.images = images;
        this.search = search;
        this.cache = cache;
    }

    /**
     * One storefront search. Every filter is optional; an unknown category or seller slug gives an
     * empty page. Deep pages are refused (more than {@value #MAX_DEPTH} results in): OFFSET still
     * reads every skipped row, and nobody pages that far; a narrower search is the answer.
     */
    @Transactional(readOnly = true)
    public PageResponse<StorefrontProductResponse> list(StorefrontQuery query) {
        PageRequest request = PageRequest.of(query.page(), query.size());
        if ((long) (query.page() + 1) * query.size() > MAX_DEPTH) {
            throw RequestValidationException.of("page", "is too deep: the storefront shows the first " + MAX_DEPTH
                    + " results. Narrow the search instead.");
        }
        if (query.minPrice() != null && query.maxPrice() != null && query.minPrice().compareTo(query.maxPrice()) > 0) {
            throw RequestValidationException.of("minPrice", "must not be above maxPrice");
        }
        UUID categoryId = null;
        if (query.categorySlug() != null) {
            Optional<Category> category = categories.findBySlug(Category.normalizeSlug(query.categorySlug()));
            if (category.isEmpty()) {
                return PageResponse.from(Page.<StorefrontProductResponse>empty(request), Function.identity());
            }
            categoryId = category.get().getId();
        }
        UUID organizationId = null;
        if (query.sellerSlug() != null) {
            Optional<SellerInfo> seller = sellers.findActiveBySlug(query.sellerSlug());
            if (seller.isEmpty()) {
                return PageResponse.from(Page.<StorefrontProductResponse>empty(request), Function.identity());
            }
            organizationId = seller.get().id();
        }

        ProductSearchRepository.Result found = search.search(new ProductSearchRepository.Criteria(query.text(), categoryId,
                organizationId, query.minPrice() == null ? null : MinorUnits.toMinor(query.minPrice()),
                query.maxPrice() == null ? null : MinorUnits.toMinor(query.maxPrice()), query.inStockOnly(),
                query.effectiveSort(), (int) request.getOffset(), query.size()));
        // Load by ID, then put back in the search's order.
        Map<UUID, Product> byId = products.findAllById(found.ids()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Product> ordered = found.ids().stream().map(byId::get).filter(Objects::nonNull).toList();

        Map<UUID, Category> categoryById = categories.findAllById(ordered.stream().map(Product::getCategoryId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        Map<UUID, SellerInfo> sellerById = sellers.activeSellers(ordered.stream().map(Product::getOrganizationId)
                .distinct().toList());
        Map<UUID, List<ProductImage>> imagesByProduct = images.findByProductIdInOrderByIdAsc(
                        ordered.stream().map(Product::getId).toList()).stream()
                .collect(Collectors.groupingBy(ProductImage::getProductId));
        // A seller suspended between the queries is dropped rather than shown.
        List<StorefrontProductResponse> content = ordered.stream()
                .filter(product -> sellerById.containsKey(product.getOrganizationId()))
                .map(product -> StorefrontProductResponse.from(product, categoryById.get(product.getCategoryId()),
                        sellerById.get(product.getOrganizationId()), imagesByProduct.getOrDefault(product.getId(), List.of())))
                .toList();
        return PageResponse.from(new PageImpl<>(content, request, found.total()), Function.identity());
    }

    /**
     * A product page, cached for {@value #PRODUCT_TTL_SECONDS} seconds: the busiest public read.
     * Seller edits evict it; stock and seller status changes show within the TTL, and checkout
     * re-checks both anyway, so a stale page can never sell what is gone.
     */
    @Transactional(readOnly = true)
    public StorefrontProductResponse get(UUID productId) {
        return cache.get(productKey(productId), Duration.ofSeconds(PRODUCT_TTL_SECONDS), PRODUCT, () -> load(productId));
    }

    static String productKey(UUID productId) {
        return "product:" + productId;
    }

    private StorefrontProductResponse load(UUID productId) {
        Product product = products.findById(productId).filter(Product::isOnSale)
                .orElseThrow(() -> new ResourceNotFoundException("Product"));
        SellerInfo seller = sellers.activeSellers(List.of(product.getOrganizationId())).get(product.getOrganizationId());
        if (seller == null) {
            throw new ResourceNotFoundException("Product");
        }
        Category category = product.getCategoryId() == null ? null
                : categories.findById(product.getCategoryId()).orElse(null);
        return StorefrontProductResponse.from(product, category, seller, images.findByProductIdOrderByIdAsc(productId));
    }
}
