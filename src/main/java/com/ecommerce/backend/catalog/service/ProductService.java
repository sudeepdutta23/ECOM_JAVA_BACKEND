package com.ecommerce.backend.catalog.service;

import com.ecommerce.backend.catalog.ProductStatus;
import com.ecommerce.backend.catalog.dto.ProductRequest;
import com.ecommerce.backend.catalog.dto.ProductResponse;
import com.ecommerce.backend.catalog.entity.Product;
import com.ecommerce.backend.catalog.repository.CategoryRepository;
import com.ecommerce.backend.catalog.repository.ProductRepository;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.common.util.MinorUnits;
import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.service.OrganizationGuard;
import com.ecommerce.backend.org.service.OrganizationGuard.Intent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A seller's catalogue, as its members manage it. Follows the seller-endpoint checklist of
 * ORGANIZATIONS.md: the guard first in every method, every lookup scoped by the organisation.
 *
 * <p>Writes use {@link OrganizationGuard#requireForSellerWrite}: it share-locks the organisation, so
 * a product cannot be published, or its stock changed, just after the seller was suspended.
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final OrganizationGuard guard;
    private final CatalogProperties properties;
    private final Clock clock;

    public ProductService(ProductRepository products, CategoryRepository categories, OrganizationGuard guard,
                          CatalogProperties properties, Clock clock) {
        this.products = products;
        this.categories = categories;
        this.guard = guard;
        this.properties = properties;
        this.clock = clock;
    }

    /** A new DRAFT without stock. Visible on the storefront only after publishing. */
    @Transactional
    public ProductResponse create(UUID userId, UUID organizationId, ProductRequest request) {
        OrgMembership membership = guard.requireForSellerWrite(organizationId, userId, OrgPermission.CATALOG_MANAGE);
        Product.Details details = details(request);
        if (products.existsByOrganizationIdAndSku(organizationId, details.sku())) {
            throw skuTaken(details.sku());
        }
        boolean sellerActive = membership.organizationStatus() == OrganizationStatus.ACTIVE;
        Product product = save(Product.draft(organizationId, details, properties.currency(), sellerActive, userId));
        log.info("Product created: organizationId={}, productId={}, by={}", organizationId, product.getId(), userId);
        return ProductResponse.from(product);
    }

    /** Every member may look; in every organisation status, closed included. */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(UUID userId, UUID organizationId, ProductStatus status, int page, int size) {
        guard.require(organizationId, userId, OrgPermission.ORGANIZATION_READ, Intent.READ);
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        return PageResponse.from(status == null ? products.findByOrganizationId(organizationId, request)
                : products.findByOrganizationIdAndStatus(organizationId, status, request), ProductResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID userId, UUID organizationId, UUID productId) {
        guard.require(organizationId, userId, OrgPermission.ORGANIZATION_READ, Intent.READ);
        return ProductResponse.from(load(organizationId, productId));
    }

    /** Replaces name, SKU, description, price and category. Orders already placed keep their own copy. */
    @Transactional
    public ProductResponse update(UUID userId, UUID organizationId, UUID productId, ProductRequest request) {
        guard.requireForSellerWrite(organizationId, userId, OrgPermission.CATALOG_MANAGE);
        if (request.version() == null) {
            throw RequestValidationException.of("version", "is required when replacing a product");
        }
        Product product = load(organizationId, productId);
        if (product.getVersion() != request.version().longValue()) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "This product was changed meanwhile. Reload it and try again.");
        }
        Product.Details details = details(request);
        if (!details.sku().equals(product.getSku()) && products.existsByOrganizationIdAndSku(organizationId, details.sku())) {
            throw skuTaken(details.sku());
        }
        product.change(details);
        save(product);
        log.info("Product updated: organizationId={}, productId={}, by={}", organizationId, productId, userId);
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse publish(UUID userId, UUID organizationId, UUID productId) {
        guard.requireForSellerWrite(organizationId, userId, OrgPermission.CATALOG_MANAGE);
        Product product = load(organizationId, productId);
        if (product.getStatus() == ProductStatus.ACTIVE) {
            throw invalidStatus(product, "it is already published");
        }
        product.publish();
        products.flush();
        log.info("Product published: organizationId={}, productId={}, by={}", organizationId, productId, userId);
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse archive(UUID userId, UUID organizationId, UUID productId) {
        guard.requireForSellerWrite(organizationId, userId, OrgPermission.CATALOG_MANAGE);
        Product product = load(organizationId, productId);
        if (product.getStatus() == ProductStatus.ARCHIVED) {
            throw invalidStatus(product, "it is already archived");
        }
        product.archive();
        products.flush();
        log.info("Product archived: organizationId={}, productId={}, by={}", organizationId, productId, userId);
        return ProductResponse.from(product);
    }

    /**
     * Adds or removes stock in one atomic statement: parallel adjustments (and, from step 6.3, sales)
     * all count, and stock never goes below zero.
     */
    @Transactional
    public ProductResponse adjustStock(UUID userId, UUID organizationId, UUID productId, int delta) {
        guard.requireForSellerWrite(organizationId, userId, OrgPermission.CATALOG_MANAGE);
        if (delta == 0) {
            throw RequestValidationException.of("delta", "must not be zero");
        }
        if (products.adjustStock(productId, organizationId, delta, Instant.now(clock)) == 0) {
            Product product = load(organizationId, productId); // 404 if it is not this seller's
            throw new BusinessException(ErrorCode.CATALOG_STOCK_INSUFFICIENT, "Stock is " + product.getStock()
                    + "; it cannot be changed by " + delta + ".");
        }
        log.info("Stock adjusted: organizationId={}, productId={}, delta={}, by={}", organizationId, productId, delta, userId);
        return ProductResponse.from(load(organizationId, productId));
    }

    // ------------------------------------------------------------------ internals

    private Product.Details details(ProductRequest request) {
        if (request.categoryId() != null && !categories.existsById(request.categoryId())) {
            throw RequestValidationException.of("categoryId", "does not exist");
        }
        return new Product.Details(request.sku(), request.name(), request.description(),
                MinorUnits.toMinor(request.price()), request.categoryId());
    }

    /** saveAndFlush: a parallel request with the same SKU fails here, on the unique key, as a clean 409. */
    private Product save(Product product) {
        try {
            return products.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("uk_products_organization_sku")) {
                throw skuTaken(product.getSku());
            }
            throw e;
        }
    }

    private Product load(UUID organizationId, UUID productId) {
        return products.findByIdAndOrganizationId(productId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Product"));
    }

    private static BusinessException skuTaken(String sku) {
        return new BusinessException(ErrorCode.CATALOG_SKU_TAKEN, "Your shop already has a product with SKU " + sku + ".");
    }

    private static BusinessException invalidStatus(Product product, String why) {
        return new BusinessException(ErrorCode.CATALOG_INVALID_STATUS,
                "This product is " + product.getStatus() + ": " + why + ".");
    }
}
