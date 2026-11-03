package com.ecommerce.backend.order.repository;

import com.ecommerce.backend.order.entity.Checkout;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckoutRepository extends JpaRepository<Checkout, UUID> {

    /** The idempotency lookup. Served by {@code uk_checkouts_customer_key}. */
    Optional<Checkout> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    Optional<Checkout> findByIdAndCustomerId(UUID id, UUID customerId);

    /** Payment: one charge per checkout, even for two "pay" clicks at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Checkout c where c.id = :id and c.customerId = :customerId")
    Optional<Checkout> lockForPayment(@Param("id") UUID id, @Param("customerId") UUID customerId);

    /**
     * Serializes one customer's checkouts until commit: a retry sent while the first attempt is
     * still running waits, then finds it by its key and replays it, instead of buying twice.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    int lockCheckoutsOf(@Param("namespace") int namespace, @Param("key") int key);
}
