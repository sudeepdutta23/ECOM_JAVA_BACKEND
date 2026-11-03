package com.ecommerce.backend.order.service;

import com.ecommerce.backend.catalog.ProductSnapshot;
import com.ecommerce.backend.catalog.service.CatalogProperties;
import com.ecommerce.backend.catalog.service.ProductCatalog;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.util.MinorUnits;
import com.ecommerce.backend.order.dto.CheckoutRequest;
import com.ecommerce.backend.order.dto.CheckoutResponse;
import com.ecommerce.backend.order.entity.AddressSnapshot;
import com.ecommerce.backend.order.entity.CartItem;
import com.ecommerce.backend.order.entity.Checkout;
import com.ecommerce.backend.order.entity.OrderLine;
import com.ecommerce.backend.order.entity.SalesOrder;
import com.ecommerce.backend.order.repository.CartItemRepository;
import com.ecommerce.backend.order.repository.CheckoutRepository;
import com.ecommerce.backend.order.repository.SalesOrderRepository;
import com.ecommerce.backend.org.SellerInfo;
import com.ecommerce.backend.org.service.SellerDirectory;
import com.ecommerce.backend.profile.PersonalProfile;
import com.ecommerce.backend.profile.service.ProfileReader;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the cart into one order per seller, in one transaction: either every order is placed and
 * every unit reserved, or nothing happened (ORDERS.md, section 3).
 *
 * <p>Locks, always in this order, so two checkouts (or a checkout and a suspension) can never
 * deadlock: the account (FOR SHARE, against erasure), the customer's checkouts (advisory, for
 * idempotency), the sellers in ID order (FOR SHARE, against suspension), the products in ID order
 * (row locks taken by the stock reservation).
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** Advisory-lock namespace for one customer's checkouts; 4001 and 5001 are taken. */
    static final int CHECKOUT_LOCK_NAMESPACE = 6_001;
    /** Must match {@code ck_checkouts_key_format} (V15): a UUID fits, so does any client-made token. */
    static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final CartItemRepository cartItems;
    private final CheckoutRepository checkouts;
    private final SalesOrderRepository orders;
    private final ProductCatalog catalog;
    private final SellerDirectory sellers;
    private final ProfileReader profiles;
    private final UserDirectory users;
    private final CatalogProperties catalogProperties;

    public CheckoutService(CartItemRepository cartItems, CheckoutRepository checkouts, SalesOrderRepository orders,
                           ProductCatalog catalog, SellerDirectory sellers, ProfileReader profiles, UserDirectory users,
                           CatalogProperties catalogProperties) {
        this.cartItems = cartItems;
        this.checkouts = checkouts;
        this.orders = orders;
        this.catalog = catalog;
        this.sellers = sellers;
        this.profiles = profiles;
        this.users = users;
        this.catalogProperties = catalogProperties;
    }

    /** The checkout, and whether it was replayed for a key already used. */
    public record Result(CheckoutResponse checkout, boolean replayed) {
    }

    @Transactional
    public Result checkout(UUID userId, String idempotencyKey, CheckoutRequest request) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw RequestValidationException.of("Idempotency-Key",
                    "header is required: 8 to 64 letters, digits, '-' or '_', new for every purchase (a UUID works)");
        }
        if (!users.lockIfActive(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "This account is no longer available.");
        }
        checkouts.lockCheckoutsOf(CHECKOUT_LOCK_NAMESPACE, userId.hashCode());
        Optional<Checkout> earlier = checkouts.findByCustomerIdAndIdempotencyKey(userId, idempotencyKey);
        if (earlier.isPresent()) {
            log.info("Checkout replayed: checkoutId={}, userId={}", earlier.get().getId(), userId);
            return new Result(response(earlier.get()), true);
        }

        // 1. The cart, at current prices, must be fully buyable and cost what the customer saw.
        List<CartItem> lines = cartItems.findByUserIdOrderByAddedAtAscProductIdAsc(userId);
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_CART_NOT_READY, "Your cart is empty.");
        }
        Map<UUID, ProductSnapshot> products = catalog.find(lines.stream().map(CartItem::getProductId).toList());
        Map<UUID, SellerInfo> sellerById = sellers.activeSellers(products.values().stream()
                .map(ProductSnapshot::organizationId).distinct().toList());
        long total = 0;
        for (CartItem line : lines) {
            ProductSnapshot product = products.get(line.getProductId());
            if (!product.canSupply(line.getQuantity()) || !sellerById.containsKey(product.organizationId())) {
                throw new BusinessException(ErrorCode.ORDER_CART_NOT_READY, "'" + product.name()
                        + "' cannot be bought in this quantity right now. Check your cart.");
            }
            total = Math.addExact(total, Math.multiplyExact(product.priceMinor(), line.getQuantity()));
        }
        if (MinorUnits.toMinor(request.expectedTotal()) != total) {
            throw new BusinessException(ErrorCode.ORDER_TOTAL_CHANGED, "Prices changed: your cart now costs "
                    + MinorUnits.toAmount(total) + " " + catalogProperties.currency() + ". Check your cart and confirm again.");
        }

        // 2. Addresses: the customer's own, copied into the orders.
        AddressSnapshot shipping = address(userId, request.shippingAddressId(), "shippingAddressId");
        AddressSnapshot billing = request.billingAddressId() == null ? shipping
                : address(userId, request.billingAddressId(), "billingAddressId");

        // 3. Lock the sellers, then reserve the stock, each in ID order.
        for (UUID sellerId : new TreeSet<>(sellerById.keySet())) {
            if (!sellers.lockIfSelling(sellerId)) {
                throw new BusinessException(ErrorCode.ORDER_SELLER_NOT_SELLING,
                        sellerById.get(sellerId).name() + " has stopped selling. Check your cart.");
            }
        }
        for (CartItem line : lines.stream().sorted(Comparator.comparing(CartItem::getProductId)).toList()) {
            if (!catalog.reserve(line.getProductId(), line.getQuantity())) {
                throw new BusinessException(ErrorCode.ORDER_OUT_OF_STOCK, "'" + products.get(line.getProductId()).name()
                        + "' just sold out. Nothing was bought; check your cart.");
            }
        }

        // 4. Write the checkout and one order per seller (after the reservations: they clear the context).
        Checkout checkout = checkouts.saveAndFlush(Checkout.of(userId, idempotencyKey, total, catalogProperties.currency()));
        Map<UUID, List<OrderLine>> linesBySeller = new LinkedHashMap<>();
        for (CartItem line : lines) {
            ProductSnapshot product = products.get(line.getProductId());
            linesBySeller.computeIfAbsent(product.organizationId(), id -> new ArrayList<>()).add(
                    new OrderLine(product.id(), product.sku(), product.name(), product.priceMinor(), line.getQuantity()));
        }
        List<SalesOrder> placed = new ArrayList<>();
        linesBySeller.forEach((sellerId, sellerLines) -> placed.add(orders.save(SalesOrder.place(checkout.getId(),
                sellerId, sellerById.get(sellerId).name(), userId, catalogProperties.currency(), sellerLines, shipping, billing))));
        orders.flush();
        CheckoutResponse response = CheckoutResponse.from(checkout, placed);
        cartItems.deleteAllByUserId(userId);
        log.info("Checkout completed: checkoutId={}, userId={}, orders={}", checkout.getId(), userId, placed.size());
        return new Result(response, false);
    }

    @Transactional(readOnly = true)
    public CheckoutResponse get(UUID userId, UUID checkoutId) {
        return response(checkouts.findByIdAndCustomerId(checkoutId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Checkout")));
    }

    private CheckoutResponse response(Checkout checkout) {
        return CheckoutResponse.from(checkout, orders.findByCheckoutIdOrderByIdAsc(checkout.getId()));
    }

    private AddressSnapshot address(UUID userId, UUID addressId, String field) {
        PersonalProfile.Address address = profiles.address(userId, addressId)
                .orElseThrow(() -> RequestValidationException.of(field, "is not one of your saved addresses"));
        return new AddressSnapshot(address.recipientName(), address.company(), address.line1(), address.line2(),
                address.city(), address.region(), address.postalCode(), address.countryCode(), address.phoneNumber());
    }
}
