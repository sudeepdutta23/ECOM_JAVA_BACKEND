package com.ecommerce.backend.catalog.service;

import com.ecommerce.backend.catalog.dto.ProductImageResponse;
import com.ecommerce.backend.catalog.entity.Product;
import com.ecommerce.backend.catalog.entity.ProductImage;
import com.ecommerce.backend.catalog.repository.ProductImageRepository;
import com.ecommerce.backend.catalog.repository.ProductRepository;
import com.ecommerce.backend.common.cache.JsonCache;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.storage.FileStorage;
import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.service.OrganizationGuard;
import com.ecommerce.backend.org.service.OrganizationGuard.Intent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Product images: seller uploads, deletes and previews; the storefront serves images of products
 * on sale. File and row are kept consistent with the transaction:
 *
 * <ul>
 *   <li>Upload: the file is written first, the row inserted after; if the transaction rolls back,
 *       the file is deleted again, so no orphan stays behind.</li>
 *   <li>Delete: the row goes in the transaction, the file only after commit, so a rollback never
 *       leaves a row pointing to a missing file.</li>
 * </ul>
 */
@Service
public class ProductImageService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);

    /** What the controller streams. */
    public record Content(byte[] bytes, String contentType) {
    }

    private final ProductImageRepository images;
    private final ProductRepository products;
    private final OrganizationGuard guard;
    private final ImageProcessor processor;
    private final FileStorage storage;
    private final ImageProperties properties;
    private final JsonCache cache;

    public ProductImageService(ProductImageRepository images, ProductRepository products, OrganizationGuard guard,
                               ImageProcessor processor, FileStorage storage, ImageProperties properties, JsonCache cache) {
        this.images = images;
        this.products = products;
        this.guard = guard;
        this.processor = processor;
        this.storage = storage;
        this.properties = properties;
        this.cache = cache;
    }

    @Transactional
    public ProductImageResponse upload(UUID userId, UUID organizationId, UUID productId, byte[] upload) {
        guard.requireForSellerWrite(organizationId, userId, OrgPermission.CATALOG_MANAGE);
        product(organizationId, productId);
        if (images.countByProductId(productId) >= properties.maxImagesPerProduct()) {
            throw new BusinessException(ErrorCode.CATALOG_IMAGE_LIMIT, "A product has at most "
                    + properties.maxImagesPerProduct() + " images. Delete one first.");
        }
        ImageProcessor.Processed image = processor.process(upload);
        String key = "product-images/" + organizationId + "/" + UUID.randomUUID() + "." + image.extension();
        storage.store(key, image.bytes());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storage.delete(key);
                }
            }
        });
        ProductImage saved = images.save(ProductImage.of(productId, organizationId, key, image.contentType(),
                image.bytes().length, image.width(), image.height()));
        cache.evictAfterCommit(StorefrontService.productKey(productId)); // the page lists its images
        log.info("Product image uploaded: organizationId={}, productId={}, imageId={}, bytes={}", organizationId,
                productId, saved.getId(), image.bytes().length);
        return ProductImageResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ProductImageResponse> list(UUID userId, UUID organizationId, UUID productId) {
        guard.require(organizationId, userId, OrgPermission.ORGANIZATION_READ, Intent.READ);
        product(organizationId, productId);
        return images.findByProductIdOrderByIdAsc(productId).stream().map(ProductImageResponse::from).toList();
    }

    @Transactional
    public void delete(UUID userId, UUID organizationId, UUID productId, UUID imageId) {
        guard.requireForSellerWrite(organizationId, userId, OrgPermission.CATALOG_MANAGE);
        ProductImage image = images.findByIdAndProductIdAndOrganizationId(imageId, productId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Image"));
        images.delete(image);
        String key = image.getStorageKey();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.delete(key);
            }
        });
        cache.evictAfterCommit(StorefrontService.productKey(productId)); // the page lists its images
        log.info("Product image deleted: organizationId={}, productId={}, imageId={}", organizationId, productId, imageId);
    }

    /** The seller's preview, also of drafts. */
    @Transactional(readOnly = true)
    public Content sellerContent(UUID userId, UUID organizationId, UUID productId, UUID imageId) {
        guard.require(organizationId, userId, OrgPermission.ORGANIZATION_READ, Intent.READ);
        return content(images.findByIdAndProductIdAndOrganizationId(imageId, productId, organizationId));
    }

    /** Public: only images of products on sale, like the storefront itself. */
    @Transactional(readOnly = true)
    public Content publicContent(UUID productId, UUID imageId) {
        if (!products.findById(productId).map(Product::isOnSale).orElse(false)) {
            throw new ResourceNotFoundException("Image");
        }
        return content(images.findByIdAndProductId(imageId, productId));
    }

    private Content content(java.util.Optional<ProductImage> found) {
        ProductImage image = found.orElseThrow(() -> new ResourceNotFoundException("Image"));
        byte[] bytes = storage.read(image.getStorageKey()).orElseThrow(() -> new ResourceNotFoundException("Image"));
        return new Content(bytes, image.getContentType());
    }

    private Product product(UUID organizationId, UUID productId) {
        return products.findByIdAndOrganizationId(productId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Product"));
    }
}
