package com.ecommerce.backend.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.catalog.ProductSnapshot;
import com.ecommerce.backend.catalog.service.CatalogProperties;
import com.ecommerce.backend.catalog.service.ProductCatalog;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.order.dto.CartResponse;
import com.ecommerce.backend.order.entity.CartItem;
import com.ecommerce.backend.order.repository.CartItemRepository;
import com.ecommerce.backend.org.SellerInfo;
import com.ecommerce.backend.org.service.SellerDirectory;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CartServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();

    private final CartItemRepository items = mock(CartItemRepository.class);
    private final ProductCatalog catalog = mock(ProductCatalog.class);
    private final SellerDirectory sellers = mock(SellerDirectory.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final CartService service = new CartService(items, catalog, sellers, users, new CatalogProperties("EUR"),
            new OrderProperties(2, Duration.ofMinutes(30)), Clock.fixed(NOW, ZoneOffset.UTC));

    private final List<CartItem> lines = new ArrayList<>();
    private final Map<UUID, ProductSnapshot> products = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(users.lockIfActive(USER)).thenReturn(true);
        when(items.findByUserIdOrderByAddedAtAscProductIdAsc(USER)).thenReturn(lines);
        when(catalog.find(anyCollection())).thenAnswer(i -> {
            Map<UUID, ProductSnapshot> found = new HashMap<>();
            for (Object id : (java.util.Collection<?>) i.getArgument(0)) {
                if (products.containsKey(id)) {
                    found.put((UUID) id, products.get(id));
                }
            }
            return found;
        });
        when(sellers.activeSellers(anyCollection())).thenReturn(Map.of(SELLER, new SellerInfo(SELLER, "acme", "Acme")));
    }

    private UUID product(long priceMinor, int stock, boolean onSale) {
        UUID id = UUID.randomUUID();
        products.put(id, new ProductSnapshot(id, SELLER, "SKU", "Mug", priceMinor, "EUR", stock, onSale));
        return id;
    }

    private static CartItem cartItem(UUID productId, int quantity) {
        try {
            var constructor = CartItem.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            CartItem item = constructor.newInstance();
            ReflectionTestUtils.setField(item, "userId", USER);
            ReflectionTestUtils.setField(item, "productId", productId);
            ReflectionTestUtils.setField(item, "quantity", quantity);
            return item;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anEmptyCartIsNotReadyForCheckout() {
        CartResponse cart = service.view(USER);

        assertThat(cart.lines()).isEmpty();
        assertThat(cart.total()).isEqualByComparingTo("0.00");
        assertThat(cart.currency()).isEqualTo("EUR");
        assertThat(cart.readyForCheckout()).isFalse();
    }

    @Test
    void theTotalCountsOnlyLinesThatCanBeBoughtAtTodaysPrices() {
        lines.add(cartItem(product(1999, 10, true), 2));      // 39.98
        lines.add(cartItem(product(500, 1, true), 3));        // only 1 left
        lines.add(cartItem(product(700, 10, false), 1));      // archived

        CartResponse cart = service.view(USER);

        assertThat(cart.total()).isEqualByComparingTo("39.98");
        assertThat(cart.itemCount()).isEqualTo(2);
        assertThat(cart.readyForCheckout()).isFalse();
        assertThat(cart.lines()).extracting(CartResponse.Line::problem)
                .containsExactly(null, CartResponse.Problem.NOT_ENOUGH_STOCK, CartResponse.Problem.NOT_ON_SALE);
        assertThat(cart.lines().get(1).lineTotal()).isEqualByComparingTo("15.00");
    }

    @Test
    void aProductOfASellerThatStoppedSellingIsNotOnSale() {
        UUID other = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        products.put(id, new ProductSnapshot(id, other, "SKU", "Mug", 100, "EUR", 5, true)); // seller not in the directory
        lines.add(cartItem(id, 1));

        CartResponse.Line line = service.view(USER).lines().get(0);

        assertThat(line.problem()).isEqualTo(CartResponse.Problem.NOT_ON_SALE);
        assertThat(line.seller()).isNull();
    }

    @Test
    void onlyProductsOnSaleCanBeAddedAndTheQuantityIsSetNotAdded() {
        UUID onSale = product(100, 5, true);
        UUID archived = product(100, 5, false);

        service.setQuantity(USER, onSale, 3);
        verify(items).upsert(USER, onSale, 3, NOW);

        assertThatThrownBy(() -> service.setQuantity(USER, archived, 1)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.setQuantity(USER, UUID.randomUUID(), 1)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aFullCartAcceptsNoNewProductButChangesToExistingLines() {
        UUID existing = product(100, 5, true);
        UUID fresh = product(100, 5, true);
        when(items.countByUserId(USER)).thenReturn(2L);
        when(items.existsByUserIdAndProductId(USER, existing)).thenReturn(true);

        service.setQuantity(USER, existing, 4);
        assertThatThrownBy(() -> service.setQuantity(USER, fresh, 1)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ORDER_CART_FULL);
        verify(items, never()).upsert(eq(USER), eq(fresh), anyInt(), any());
    }

    @Test
    void anInactiveAccountCannotFillACart() {
        when(users.lockIfActive(USER)).thenReturn(false);

        assertThatThrownBy(() -> service.setQuantity(USER, product(100, 5, true), 1)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(items, never()).upsert(any(), any(), anyInt(), any());
    }
}
