package com.ecommerce.backend.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.catalog.ProductSnapshot;
import com.ecommerce.backend.catalog.service.CatalogProperties;
import com.ecommerce.backend.catalog.service.ProductCatalog;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.order.dto.CheckoutRequest;
import com.ecommerce.backend.order.entity.CartItem;
import com.ecommerce.backend.order.entity.Checkout;
import com.ecommerce.backend.order.entity.SalesOrder;
import com.ecommerce.backend.order.repository.CartItemRepository;
import com.ecommerce.backend.order.repository.CheckoutRepository;
import com.ecommerce.backend.order.repository.SalesOrderRepository;
import com.ecommerce.backend.org.SellerInfo;
import com.ecommerce.backend.org.service.SellerDirectory;
import com.ecommerce.backend.profile.PersonalProfile;
import com.ecommerce.backend.profile.service.ProfileReader;
import com.ecommerce.backend.rbac.service.UserDirectory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class CheckoutServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final String KEY = "6f1c2a1e-0d4b-4c7e-9b1a-3e2f5d6c7b8a";
    private static final UUID ADDRESS = UUID.randomUUID();
    // Seller and product IDs whose natural order differs from the cart's order.
    private static final UUID SELLER_A = new UUID(0, 2);
    private static final UUID SELLER_B = new UUID(0, 1);
    private static final UUID PRODUCT_A = new UUID(0, 20);
    private static final UUID PRODUCT_B = new UUID(0, 10);

    private final CartItemRepository cartItems = mock(CartItemRepository.class);
    private final CheckoutRepository checkouts = mock(CheckoutRepository.class);
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final ProductCatalog catalog = mock(ProductCatalog.class);
    private final SellerDirectory sellers = mock(SellerDirectory.class);
    private final ProfileReader profiles = mock(ProfileReader.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final CheckoutService service = new CheckoutService(cartItems, checkouts, orders, catalog, sellers, profiles,
            users, new CatalogProperties("EUR"));

    private final List<CartItem> cart = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(users.lockIfActive(USER)).thenReturn(true);
        when(checkouts.findByCustomerIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.empty());
        when(cartItems.findByUserIdOrderByAddedAtAscProductIdAsc(USER)).thenReturn(cart);
        when(catalog.find(anyCollection())).thenReturn(Map.of(
                PRODUCT_A, new ProductSnapshot(PRODUCT_A, SELLER_A, "A", "Mug", 1000, "EUR", 5, true),
                PRODUCT_B, new ProductSnapshot(PRODUCT_B, SELLER_B, "B", "Book", 500, "EUR", 5, true)));
        when(sellers.activeSellers(anyCollection())).thenReturn(Map.of(
                SELLER_A, new SellerInfo(SELLER_A, "a", "A Shop"), SELLER_B, new SellerInfo(SELLER_B, "b", "B Shop")));
        when(sellers.lockIfSelling(any())).thenReturn(true);
        when(catalog.reserve(any(), anyInt())).thenReturn(true);
        when(profiles.address(USER, ADDRESS)).thenReturn(Optional.of(new PersonalProfile.Address(ADDRESS, null, "Ada", null,
                "Street 1", null, "Berlin", null, "10117", "DE", null, true, true, null, null)));
        when(checkouts.saveAndFlush(any(Checkout.class))).thenAnswer(i -> withId(i.getArgument(0)));
        when(orders.save(any(SalesOrder.class))).thenAnswer(i -> {
            SalesOrder order = withId(i.getArgument(0));
            ReflectionTestUtils.setField(order, "version", 0L); // what Hibernate sets on insert
            return order;
        });
        cart.add(line(PRODUCT_A, 1));
        cart.add(line(PRODUCT_B, 2));
    }

    private static <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }

    private static CartItem line(UUID productId, int quantity) {
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

    private static CheckoutRequest request(String expectedTotal) {
        return new CheckoutRequest(ADDRESS, null, new BigDecimal(expectedTotal));
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void locksAreTakenInOneGlobalOrderSoCheckoutsCannotDeadlock() {
        service.checkout(USER, KEY, request("20.00"));

        InOrder order = inOrder(users, checkouts, sellers, catalog);
        order.verify(users).lockIfActive(USER);
        order.verify(checkouts).lockCheckoutsOf(CheckoutService.CHECKOUT_LOCK_NAMESPACE, USER.hashCode());
        order.verify(sellers).lockIfSelling(SELLER_B); // lower ID first, whatever the cart's order
        order.verify(sellers).lockIfSelling(SELLER_A);
        order.verify(catalog).reserve(PRODUCT_B, 2);
        order.verify(catalog).reserve(PRODUCT_A, 1);
        order.verify(checkouts).saveAndFlush(any(Checkout.class));
    }

    @Test
    void oneOrderPerSellerWithTheServersTotalAndTheCartIsEmptied() {
        CheckoutService.Result result = service.checkout(USER, KEY, request("20.00"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.checkout().orders()).hasSize(2);
        assertThat(result.checkout().total()).isEqualByComparingTo("20.00");
        verify(cartItems).deleteAllByUserId(USER);
    }

    @Test
    void aSellerSuspendedBetweenTheCartCheckAndTheLockStopsEverything() {
        when(sellers.lockIfSelling(SELLER_A)).thenReturn(false);

        assertCode(() -> service.checkout(USER, KEY, request("20.00")), ErrorCode.ORDER_SELLER_NOT_SELLING);
        verify(catalog, never()).reserve(any(), anyInt());
        verify(checkouts, never()).saveAndFlush(any());
    }

    @Test
    void aProductThatSoldOutAtTheLastMomentStopsEverything() {
        when(catalog.reserve(PRODUCT_A, 1)).thenReturn(false);

        assertCode(() -> service.checkout(USER, KEY, request("20.00")), ErrorCode.ORDER_OUT_OF_STOCK);
        verify(checkouts, never()).saveAndFlush(any());
        verify(cartItems, never()).deleteAllByUserId(any());
    }

    @Test
    void aKeyAlreadyUsedReplaysTheEarlierCheckoutWithoutReadingTheCart() {
        Checkout earlier = withId(Checkout.of(USER, KEY, 2000, "EUR"));
        when(checkouts.findByCustomerIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(earlier));

        CheckoutService.Result result = service.checkout(USER, KEY, request("1.00"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.checkout().id()).isEqualTo(earlier.getId());
        verifyNoInteractions(cartItems, catalog);
    }

    @Test
    void aMissingOrMalformedKeyIsRejectedBeforeAnythingIsLocked() {
        for (String key : new String[] {null, "short", "has spaces in it", "x".repeat(65)}) {
            assertThatThrownBy(() -> service.checkout(USER, key, request("20.00"))).as(String.valueOf(key))
                    .isInstanceOf(RequestValidationException.class);
        }
        verifyNoInteractions(users, checkouts);
    }
}
