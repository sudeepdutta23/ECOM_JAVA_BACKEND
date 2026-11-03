package com.ecommerce.backend.catalog.service;

import com.ecommerce.backend.catalog.dto.StorefrontProductResponse;
import com.ecommerce.backend.catalog.entity.Category;
import com.ecommerce.backend.catalog.entity.Product;
import com.ecommerce.backend.catalog.entity.ProductImage;
import com.ecommerce.backend.catalog.repository.CategoryRepository;
import com.ecommerce.backend.catalog.repository.ProductImageRepository;
import com.ecommerce.backend.catalog.repository.ProductRepository;
import com.ecommerce.backend.catalog.repository.StorefrontSearch;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.org.SellerInfo;
import com.ecommerce.backend.org.service.SellerDirectory;
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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public storefront: ACTIVE products of ACTIVE sellers, nothing else. A draft, an archived
 * product and anything of a pending or suspended seller answer 404, like an ID that never existed.
 *
 * <p>A page costs four queries whatever its size: the products, their categories, their sellers, their images.
 * Full-text search and richer filters come in Phase 10.
 */
@Service
public class StorefrontService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final SellerDirectory sellers;
    private final ProductImageRepository images;

    public StorefrontService(ProductRepository products, CategoryRepository categories, SellerDirectory sellers,
                             ProductImageRepository images) {
        this.products = products;
        this.categories = categories;
        this.sellers = sellers;
        this.images = images;
    }

    /** @param categorySlug optional; @param sellerSlug optional. An unknown slug gives an empty page. */
    @Transactional(readOnly = true)
    public PageResponse<StorefrontProductResponse> list(String categorySlug, String sellerSlug, int page, int size) {
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        UUID categoryId = null;
        if (categorySlug != null) {
            Optional<Category> category = categories.findBySlug(Category.normalizeSlug(categorySlug));
            if (category.isEmpty()) {
                return PageResponse.from(Page.<StorefrontProductResponse>empty(request), Function.identity());
            }
            categoryId = category.get().getId();
        }
        UUID organizationId = null;
        if (sellerSlug != null) {
            Optional<SellerInfo> seller = sellers.findActiveBySlug(sellerSlug);
            if (seller.isEmpty()) {
                return PageResponse.from(Page.<StorefrontProductResponse>empty(request), Function.identity());
            }
            organizationId = seller.get().id();
        }

        Page<Product> found = products.findAll(StorefrontSearch.onSale(categoryId, organizationId), request);
        Map<UUID, Category> categoryById = categories.findAllById(found.stream().map(Product::getCategoryId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        Map<UUID, SellerInfo> sellerById = sellers.activeSellers(found.stream().map(Product::getOrganizationId)
                .distinct().toList());
        Map<UUID, List<ProductImage>> imagesByProduct = images.findByProductIdInOrderByIdAsc(
                        found.stream().map(Product::getId).toList()).stream()
                .collect(Collectors.groupingBy(ProductImage::getProductId));
        // A seller suspended between the two queries is dropped rather than shown.
        List<StorefrontProductResponse> content = found.stream()
                .filter(product -> sellerById.containsKey(product.getOrganizationId()))
                .map(product -> StorefrontProductResponse.from(product, categoryById.get(product.getCategoryId()),
                        sellerById.get(product.getOrganizationId()), imagesByProduct.getOrDefault(product.getId(), List.of())))
                .toList();
        return PageResponse.from(new PageImpl<>(content, request, found.getTotalElements()), Function.identity());
    }

    @Transactional(readOnly = true)
    public StorefrontProductResponse get(UUID productId) {
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
