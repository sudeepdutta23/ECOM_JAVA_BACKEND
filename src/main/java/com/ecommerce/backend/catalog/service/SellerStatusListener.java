package com.ecommerce.backend.catalog.service;

import com.ecommerce.backend.catalog.repository.ProductRepository;
import com.ecommerce.backend.common.cache.JsonCache;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.OrganizationStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code products.seller_active} in step with the seller's status. A plain {@code @EventListener}
 * runs synchronously, inside the status change's transaction: suspending a seller and taking its
 * products off the storefront commit together, or neither does. No window in which a suspended
 * seller is still selling.
 *
 * <p>Phase 13 moves cross-module events to an outbox; this one must stay in-transaction.
 */
@Component
class SellerStatusListener {

    private static final Logger log = LoggerFactory.getLogger(SellerStatusListener.class);

    private final ProductRepository products;
    private final JsonCache cache;

    SellerStatusListener(ProductRepository products, JsonCache cache) {
        this.products = products;
        this.cache = cache;
    }

    @EventListener
    void on(OrganizationStatusChanged event) {
        boolean active = event.status() == OrganizationStatus.ACTIVE;
        int changed = products.updateSellerActive(event.organizationId(), active);
        // Cached product pages of this seller go too, after commit: a suspension shows at once, not after the TTL.
        products.findIdsByOrganizationId(event.organizationId())
                .forEach(id -> cache.evictAfterCommit(StorefrontService.productKey(id)));
        log.info("Seller products {} the storefront: organizationId={}, products={}",
                active ? "back on" : "taken off", event.organizationId(), changed);
    }
}
