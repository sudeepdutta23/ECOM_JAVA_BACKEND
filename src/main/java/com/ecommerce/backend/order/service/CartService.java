package com.ecommerce.backend.order.service;

import com.ecommerce.backend.catalog.ProductSnapshot;
import com.ecommerce.backend.catalog.service.CatalogProperties;
import com.ecommerce.backend.catalog.service.ProductCatalog;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.util.MinorUnits;
import com.ecommerce.backend.order.dto.CartResponse;
import com.ecommerce.backend.order.entity.CartItem;
import com.ecommerce.backend.order.repository.CartItemRepository;
import com.ecommerce.backend.org.SellerInfo;
import com.ecommerce.backend.org.service.SellerDirectory;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in customer's cart. One cart per account, no ID in any URL: the account comes from the
 * token. The cart keeps quantities only; every view prices it at today's prices.
 */
@Service
public class CartService {

    private final CartItemRepository items;
    private final ProductCatalog catalog;
    private final SellerDirectory sellers;
    private final UserDirectory users;
    private final CatalogProperties catalogProperties;
    private final OrderProperties properties;
    private final Clock clock;

    public CartService(CartItemRepository items, ProductCatalog catalog, SellerDirectory sellers, UserDirectory users,
                       CatalogProperties catalogProperties, OrderProperties properties, Clock clock) {
        this.items = items;
        this.catalog = catalog;
        this.sellers = sellers;
        this.users = users;
        this.catalogProperties = catalogProperties;
        this.properties = properties;
        this.clock = clock;
    }

    /** Three queries whatever the size: the lines, their products, their sellers. */
    @Transactional(readOnly = true)
    public CartResponse view(UUID userId) {
        List<CartItem> lines = items.findByUserIdOrderByAddedAtAscProductIdAsc(userId);
        Map<UUID, ProductSnapshot> products = catalog.find(lines.stream().map(CartItem::getProductId).toList());
        Map<UUID, SellerInfo> sellerById = sellers.activeSellers(products.values().stream()
                .map(ProductSnapshot::organizationId).distinct().toList());

        List<CartResponse.Line> result = new ArrayList<>();
        long total = 0;
        int units = 0;
        boolean allBuyable = !lines.isEmpty();
        for (CartItem line : lines) {
            ProductSnapshot product = products.get(line.getProductId());
            SellerInfo seller = sellerById.get(product.organizationId());
            CartResponse.Problem problem = !product.onSale() || seller == null ? CartResponse.Problem.NOT_ON_SALE
                    : product.stock() < line.getQuantity() ? CartResponse.Problem.NOT_ENOUGH_STOCK : null;
            long lineTotal = Math.multiplyExact(product.priceMinor(), line.getQuantity());
            if (problem == null) {
                total = Math.addExact(total, lineTotal);
                units += line.getQuantity();
            } else {
                allBuyable = false;
            }
            result.add(new CartResponse.Line(product.id(), product.name(),
                    seller == null ? null : new CartResponse.Seller(seller.slug(), seller.name()),
                    MinorUnits.toAmount(product.priceMinor()), line.getQuantity(), MinorUnits.toAmount(lineTotal), problem));
        }
        return new CartResponse(result, MinorUnits.toAmount(total), catalogProperties.currency(), units, allBuyable);
    }

    /**
     * Sets a line's quantity, adding it if new. Only products on sale can be added; more units than
     * are in stock can be asked for, and the line then shows NOT_ENOUGH_STOCK until checkout decides.
     */
    @Transactional
    public CartResponse setQuantity(UUID userId, UUID productId, int quantity) {
        if (!users.lockIfActive(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "This account is no longer available.");
        }
        ProductSnapshot product = catalog.find(List.of(productId)).get(productId);
        if (product == null || !product.onSale()) {
            throw new ResourceNotFoundException("Product");
        }
        if (!items.existsByUserIdAndProductId(userId, productId) && items.countByUserId(userId) >= properties.maxCartLines()) {
            throw new BusinessException(ErrorCode.ORDER_CART_FULL, "Your cart holds " + properties.maxCartLines()
                    + " different products, the maximum. Check out or remove some first.");
        }
        items.upsert(userId, productId, quantity, Instant.now(clock));
        return view(userId);
    }

    /** Idempotent: removing a line that is not there succeeds. */
    @Transactional
    public CartResponse remove(UUID userId, UUID productId) {
        items.deleteLine(userId, productId);
        return view(userId);
    }

    @Transactional
    public void clear(UUID userId) {
        items.deleteAllByUserId(userId);
    }
}
