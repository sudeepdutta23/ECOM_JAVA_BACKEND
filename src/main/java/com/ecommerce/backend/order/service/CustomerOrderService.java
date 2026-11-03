package com.ecommerce.backend.order.service;

import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.order.dto.OrderResponse;
import com.ecommerce.backend.order.dto.OrderSummary;
import com.ecommerce.backend.order.entity.SalesOrder;
import com.ecommerce.backend.order.repository.SalesOrderRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A customer's own orders. Every lookup takes the customer: someone else's order ID is 404. */
@Service
public class CustomerOrderService {

    private static final Logger log = LoggerFactory.getLogger(CustomerOrderService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    private final SalesOrderRepository orders;
    private final OrderCancellation cancellation;

    public CustomerOrderService(SalesOrderRepository orders, OrderCancellation cancellation) {
        this.orders = orders;
        this.cancellation = cancellation;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(UUID userId, int page, int size) {
        return PageResponse.from(orders.findByCustomerId(userId, PageRequest.of(page, size, NEWEST_FIRST)), OrderSummary::from);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID userId, UUID orderId) {
        return OrderResponse.from(orders.findByIdAndCustomerId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order")));
    }

    /** Before shipping only: the stock goes back, a paid order is refunded. */
    @Transactional
    public OrderResponse cancel(UUID userId, UUID orderId, String reason) {
        SalesOrder order = orders.lockForCustomer(orderId, userId).orElseThrow(() -> new ResourceNotFoundException("Order"));
        cancellation.cancel(order, userId, reason, true);
        log.info("Order cancelled by the customer: orderId={}, userId={}", orderId, userId);
        return OrderResponse.from(order);
    }
}
