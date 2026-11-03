package com.ecommerce.backend.catalog.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.catalog.ProductStatus;
import com.ecommerce.backend.common.util.MinorUnits;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {

    private static final Product.Details MUG = new Product.Details("MUG-001", "Mug", null, 1999, null);

    @Test
    void aNewProductIsADraftThatNobodyCanBuy() {
        Product product = Product.draft(UUID.randomUUID(), MUG, "EUR", true, UUID.randomUUID());

        assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);
        assertThat(product.getStock()).isZero();
        assertThat(product.isOnSale()).isFalse();
    }

    @Test
    void publishedProductsAreOnSaleOnlyWhileTheSellerIsActive() {
        Product ofActiveSeller = Product.draft(UUID.randomUUID(), MUG, "EUR", true, null);
        Product ofPendingSeller = Product.draft(UUID.randomUUID(), MUG, "EUR", false, null);

        ofActiveSeller.publish();
        ofPendingSeller.publish();

        assertThat(ofActiveSeller.isOnSale()).isTrue();
        assertThat(ofPendingSeller.isOnSale()).isFalse();
    }

    @Test
    void theLifeCycleRefusesNoOpTransitions() {
        Product product = Product.draft(UUID.randomUUID(), MUG, "EUR", true, null);
        product.publish();
        assertThatThrownBy(product::publish).isInstanceOf(IllegalStateException.class);
        product.archive();
        assertThatThrownBy(product::archive).isInstanceOf(IllegalStateException.class);
        product.publish(); // archived products can come back
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void aPriceMustBePositive() {
        UUID seller = UUID.randomUUID();
        assertThatThrownBy(() -> Product.draft(seller, new Product.Details("A", "A", null, 0, null), "EUR", true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyIsExactAndNeverRoundedSilently() {
        assertThat(MinorUnits.toMinor(new BigDecimal("19.99"))).isEqualTo(1999);
        assertThat(MinorUnits.toMinor(new BigDecimal("5"))).isEqualTo(500);
        assertThat(MinorUnits.toAmount(1999)).isEqualByComparingTo("19.99");
        assertThat(MinorUnits.toAmount(500).toPlainString()).isEqualTo("5.00");
        assertThatThrownBy(() -> MinorUnits.toMinor(new BigDecimal("0.999"))).isInstanceOf(ArithmeticException.class);
    }
}
