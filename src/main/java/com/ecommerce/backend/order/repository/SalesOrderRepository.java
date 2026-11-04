package com.ecommerce.backend.order.repository;

import com.ecommerce.backend.order.OrderStatus;
import com.ecommerce.backend.order.entity.SalesOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/**
 * Every lookup takes its owner: the customer, or the seller (ADR-012). An order ID of someone else
 * finds nothing. Detail lookups fetch the lines in the same query (no N+1); list pages do not show
 * lines, so they never load them.
 */
public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID> {

    @EntityGraph(attributePaths = "lines")
    List<SalesOrder> findByCheckoutIdOrderByIdAsc(UUID checkoutId);

    @EntityGraph(attributePaths = "lines")
    Optional<SalesOrder> findByIdAndCustomerId(UUID id, UUID customerId);

    @EntityGraph(attributePaths = "lines")
    Optional<SalesOrder> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** "My orders". Served by {@code idx_orders_customer_id}. */
    Page<SalesOrder> findByCustomerId(UUID customerId, Pageable page);

    /** The seller's orders. Served by {@code idx_orders_organization_id} and {@code idx_orders_organization_status}. */
    Page<SalesOrder> findByOrganizationId(UUID organizationId, Pageable page);

    Page<SalesOrder> findByOrganizationIdAndStatus(UUID organizationId, OrderStatus status, Pageable page);

    /*
     * Locks for status changes, so a customer's cancellation and a seller's shipment of the same
     * order cannot both succeed. Scoped by the owner like every other lookup: another person's ID
     * locks nothing. No entity graph: PostgreSQL cannot lock the outer side of a join fetch, so the
     * lines load lazily, after the lock.
     */

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id and o.customerId = :customerId")
    Optional<SalesOrder> lockForCustomer(@Param("id") UUID id, @Param("customerId") UUID customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id and o.organizationId = :organizationId")
    Optional<SalesOrder> lockForSeller(@Param("id") UUID id, @Param("organizationId") UUID organizationId);

    /** The expiry job: unpaid orders, oldest first. Served by the partial {@code idx_orders_pending_payment}. */
    @Query("select o.id from SalesOrder o where o.status = com.ecommerce.backend.order.OrderStatus.PENDING_PAYMENT "
            + "and o.createdAt < :cutoff order by o.createdAt")
    List<UUID> findUnpaidCreatedBefore(@Param("cutoff") Instant cutoff, Pageable page);

    /** The expiry job locks one order at a time and checks it again. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id")
    Optional<SalesOrder> lockById(@Param("id") UUID id);

    /** Payment: every order of the checkout, in ID order (the same order for every caller). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.checkoutId = :checkoutId order by o.id")
    List<SalesOrder> lockAllOfCheckout(@Param("checkoutId") UUID checkoutId);
}
