package com.ecommerce.backend.order.service;

import com.ecommerce.backend.order.repository.CartItemRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the auth module calls while an account is erased, like {@code profile.service.ProfileEraser}.
 * The cart goes. Orders stay: tax law requires keeping them for years (GDPR article 17(3)(b)); they
 * keep the account's ID, which after erasure points to an anonymous row (step 6.4 documents it).
 */
@Service
public class OrderEraser {

    private final CartItemRepository cartItems;

    public OrderEraser(CartItemRepository cartItems) {
        this.cartItems = cartItems;
    }

    /** @return the number of cart lines removed */
    @Transactional(propagation = Propagation.MANDATORY)
    public int forget(UUID userId) {
        return cartItems.deleteAllByUserId(userId);
    }
}
