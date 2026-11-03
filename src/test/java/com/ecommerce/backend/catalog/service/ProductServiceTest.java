package com.ecommerce.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.ecommerce.backend.org.OrgMembership;
import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.OrgRole;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.service.OrganizationGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class ProductServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final OrganizationGuard guard = mock(OrganizationGuard.class);
    private final ProductService service = new ProductService(products, categories, guard, new CatalogProperties("EUR"),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        sellerIs(OrganizationStatus.ACTIVE);
        when(products.saveAndFlush(any(Product.class))).thenAnswer(i -> persisted(i.getArgument(0), 0));
    }

    private void sellerIs(OrganizationStatus status) {
        when(guard.requireForSellerWrite(ORG, USER, OrgPermission.CATALOG_MANAGE))
                .thenReturn(new OrgMembership(ORG, USER, OrgRole.STAFF, status));
    }

    private static Product persisted(Product product, long version) {
        if (product.getId() == null) {
            ReflectionTestUtils.setField(product, "id", UUID.randomUUID());
        }
        ReflectionTestUtils.setField(product, "version", version);
        return product;
    }

    private static ProductRequest mug(String sku, Long version) {
        return new ProductRequest(sku, "Mug", null, new BigDecimal("19.99"), null, version);
    }

    private Product existing(long version) {
        Product product = persisted(Product.draft(ORG, new Product.Details("MUG-001", "Mug", null, 1999, null), "EUR",
                true, USER), version);
        when(products.findByIdAndOrganizationId(product.getId(), ORG)).thenReturn(Optional.of(product));
        return product;
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void aNewProductIsADraftInThePlatformCurrencyPricedInCents() {
        ProductResponse created = service.create(USER, ORG, mug("MUG-001", null));

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPriceMinor()).isEqualTo(1999);
        assertThat(saved.getValue().getCurrency()).isEqualTo("EUR");
        assertThat(saved.getValue().getOrganizationId()).isEqualTo(ORG);
        assertThat(created.status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(created.price()).isEqualByComparingTo("19.99");
    }

    @Test
    void theGuardRunsBeforeAnythingIsReadOrWritten() {
        service.create(USER, ORG, mug("MUG-001", null));

        InOrder order = inOrder(guard, products);
        order.verify(guard).requireForSellerWrite(ORG, USER, OrgPermission.CATALOG_MANAGE);
        order.verify(products).existsByOrganizationIdAndSku(ORG, "MUG-001");
        order.verify(products).saveAndFlush(any(Product.class));
    }

    @Test
    void aSellerAwaitingApprovalBuildsACatalogueNobodySeesYet() {
        sellerIs(OrganizationStatus.PENDING_APPROVAL);

        service.create(USER, ORG, mug("MUG-001", null));

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isSellerActive()).isFalse();
    }

    @Test
    void anSkuIsUniquePerSellerAlsoInARace() {
        when(products.existsByOrganizationIdAndSku(ORG, "MUG-001")).thenReturn(true);
        assertCode(() -> service.create(USER, ORG, mug("MUG-001", null)), ErrorCode.CATALOG_SKU_TAKEN);

        when(products.existsByOrganizationIdAndSku(ORG, "MUG-002")).thenReturn(false);
        when(products.saveAndFlush(any(Product.class))).thenThrow(
                new DataIntegrityViolationException("duplicate key value violates \"uk_products_organization_sku\""));
        assertCode(() -> service.create(USER, ORG, mug("MUG-002", null)), ErrorCode.CATALOG_SKU_TAKEN);
    }

    @Test
    void anUnknownCategoryIsAValidationError() {
        UUID category = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(USER, ORG,
                new ProductRequest("MUG-001", "Mug", null, BigDecimal.TEN, category, null)))
                .isInstanceOf(RequestValidationException.class);
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void aSuspendedSellerCannotWriteBecauseTheGuardRefuses() {
        when(guard.requireForSellerWrite(ORG, USER, OrgPermission.CATALOG_MANAGE))
                .thenThrow(new BusinessException(ErrorCode.ORG_INVALID_STATUS, "suspended"));

        assertCode(() -> service.create(USER, ORG, mug("MUG-001", null)), ErrorCode.ORG_INVALID_STATUS);
        verifyNoInteractions(products);
    }

    @Test
    void replacingNeedsTheCurrentVersion() {
        Product product = existing(3);

        assertThatThrownBy(() -> service.update(USER, ORG, product.getId(), mug("MUG-001", null)))
                .isInstanceOf(RequestValidationException.class);
        assertCode(() -> service.update(USER, ORG, product.getId(), mug("MUG-001", 2L)), ErrorCode.CONCURRENT_MODIFICATION);

        service.update(USER, ORG, product.getId(), new ProductRequest("MUG-001", "Big mug", null,
                new BigDecimal("24.50"), null, 3L));
        assertThat(product.getName()).isEqualTo("Big mug");
        assertThat(product.getPriceMinor()).isEqualTo(2450);
    }

    @Test
    void anotherSellersProductIsNotFoundEvenThroughACorrectlyGuardedCall() {
        UUID theirs = UUID.randomUUID(); // findByIdAndOrganizationId(theirs, ORG) finds nothing

        assertThatThrownBy(() -> service.publish(USER, ORG, theirs)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(USER, ORG, theirs, mug("X", 0L))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.adjustStock(USER, ORG, theirs, 5)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void publishingTwiceIsAConflict() {
        Product product = existing(0);

        assertThat(service.publish(USER, ORG, product.getId()).status()).isEqualTo(ProductStatus.ACTIVE);
        assertCode(() -> service.publish(USER, ORG, product.getId()), ErrorCode.CATALOG_INVALID_STATUS);
        assertThat(service.archive(USER, ORG, product.getId()).status()).isEqualTo(ProductStatus.ARCHIVED);
        assertCode(() -> service.archive(USER, ORG, product.getId()), ErrorCode.CATALOG_INVALID_STATUS);
    }

    @Test
    void stockChangesAreOneAtomicStatementScopedToTheSeller() {
        Product product = existing(0);
        when(products.adjustStock(product.getId(), ORG, 25, NOW)).thenReturn(1);

        service.adjustStock(USER, ORG, product.getId(), 25);

        verify(products).adjustStock(product.getId(), ORG, 25, NOW);
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void stockCannotDropBelowZeroOrBeChangedByNothing() {
        Product product = existing(0);
        when(products.adjustStock(eq(product.getId()), eq(ORG), anyInt(), any())).thenReturn(0);

        assertCode(() -> service.adjustStock(USER, ORG, product.getId(), -1), ErrorCode.CATALOG_STOCK_INSUFFICIENT);
        assertThatThrownBy(() -> service.adjustStock(USER, ORG, product.getId(), 0))
                .isInstanceOf(RequestValidationException.class);
    }
}
