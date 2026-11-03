package com.ecommerce.backend.order.service;

import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.order.OrderEvent;
import com.ecommerce.backend.order.OrderStatus;
import com.ecommerce.backend.order.dto.OrderResponse;
import com.ecommerce.backend.order.dto.OrderSummary;
import com.ecommerce.backend.order.entity.SalesOrder;
import com.ecommerce.backend.order.repository.SalesOrderRepository;
import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.service.OrganizationGuard;
import com.ecommerce.backend.org.service.OrganizationGuard.Intent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A seller fulfilling its orders. Follows the seller-endpoint checklist: the guard first, every
 * lookup scoped by the organisation.
 *
 * <p>Fulfilment uses intent READ, so it works in every organisation status: a suspension stops new
 * sales (checkout locks the seller), not the delivery of what customers already paid for. A closing
 * seller can still ship or cancel its last orders.
 */
@Service
public class SellerOrderService {

    private static final Logger log = LoggerFactory.getLogger(SellerOrderService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    private final SalesOrderRepository orders;
    private final OrganizationGuard guard;
    private final OrderCancellation cancellation;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SellerOrderService(SalesOrderRepository orders, OrganizationGuard guard, OrderCancellation cancellation,
                              ApplicationEventPublisher events, Clock clock) {
        this.orders = orders;
        this.guard = guard;
        this.cancellation = cancellation;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(UUID userId, UUID organizationId, OrderStatus status, int page, int size) {
        guard.require(organizationId, userId, OrgPermission.ORGANIZATION_READ, Intent.READ);
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        return PageResponse.from(status == null ? orders.findByOrganizationId(organizationId, request)
                : orders.findByOrganizationIdAndStatus(organizationId, status, request), OrderSummary::from);
    }

    /** With the shipping address: the seller needs it to ship. */
    @Transactional(readOnly = true)
    public OrderResponse get(UUID userId, UUID organizationId, UUID orderId) {
        guard.require(organizationId, userId, OrgPermission.ORGANIZATION_READ, Intent.READ);
        return OrderResponse.from(orders.findByIdAndOrganizationId(orderId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Order")));
    }

    @Transactional
    public OrderResponse ship(UUID userId, UUID organizationId, UUID orderId, String trackingNumber) {
        SalesOrder order = lockOwn(userId, organizationId, orderId);
        requireStatus(order, OrderStatus.PAID, "only paid orders can be shipped");
        order.ship(trackingNumber, Instant.now(clock));
        orders.flush();
        events.publishEvent(new OrderEvent.Shipped(orderId, organizationId, order.getCustomerId(), order.getSellerName(),
                trackingNumber));
        log.info("Order shipped: organizationId={}, orderId={}, by={}", organizationId, orderId, userId);
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse markDelivered(UUID userId, UUID organizationId, UUID orderId) {
        SalesOrder order = lockOwn(userId, organizationId, orderId);
        requireStatus(order, OrderStatus.SHIPPED, "only shipped orders can be delivered");
        order.markDelivered(Instant.now(clock));
        orders.flush();
        events.publishEvent(new OrderEvent.Delivered(orderId, organizationId, order.getCustomerId(), order.getSellerName()));
        log.info("Order delivered: organizationId={}, orderId={}, by={}", organizationId, orderId, userId);
        return OrderResponse.from(order);
    }

    /** The customer is told why, so a reason is required. Stock goes back; a paid order is refunded. */
    @Transactional
    public OrderResponse cancel(UUID userId, UUID organizationId, UUID orderId, String reason) {
        SalesOrder order = lockOwn(userId, organizationId, orderId);
        if (reason == null) {
            throw RequestValidationException.of("reason", "is required: the customer is told why");
        }
        cancellation.cancel(order, userId, reason, false);
        log.info("Order cancelled by the seller: organizationId={}, orderId={}, by={}", organizationId, orderId, userId);
        return OrderResponse.from(order);
    }

    private SalesOrder lockOwn(UUID userId, UUID organizationId, UUID orderId) {
        guard.require(organizationId, userId, OrgPermission.ORDER_MANAGE, Intent.READ);
        return orders.lockForSeller(orderId, organizationId).orElseThrow(() -> new ResourceNotFoundException("Order"));
    }

    private static void requireStatus(SalesOrder order, OrderStatus expected, String why) {
        if (order.getStatus() != expected) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATUS, "This order is " + order.getStatus() + ": " + why + ".");
        }
    }
}
