package com.orderflow.order.controller;

import com.orderflow.auth.security.SecurityUtils;
import com.orderflow.common.dto.ApiResponse;
import com.orderflow.common.dto.PagedResponse;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.OrderResponse;
import com.orderflow.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for order management.
 *
 * All endpoints require authentication. Users can only access their own orders.
 * Order creation is idempotent via a client-supplied idempotency key.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Create a new order. Idempotent — duplicate requests with the same
     * idempotency key return the original order with 200 instead of 201.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {

        UUID userId = SecurityUtils.getCurrentUserId();
        OrderResponse order = orderService.createOrder(userId, request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(order, "Order created successfully"));
    }

    /**
     * List the authenticated user's orders, newest first.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<OrderResponse>>> listOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID userId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(page, Math.min(size, 50),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        PagedResponse<OrderResponse> orders = orderService.listUserOrders(userId, pageable);

        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    /**
     * Get a single order by ID. Returns 404 if the order doesn't exist
     * or doesn't belong to the authenticated user.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @PathVariable UUID orderId) {

        UUID userId = SecurityUtils.getCurrentUserId();
        OrderResponse order = orderService.getOrder(orderId, userId);

        return ResponseEntity.ok(ApiResponse.success(order));
    }

    /**
     * Cancel an order. Only PENDING and CONFIRMED orders can be cancelled.
     * Releases reserved inventory and writes a cancellation event to the outbox.
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable UUID orderId) {

        UUID userId = SecurityUtils.getCurrentUserId();
        OrderResponse order = orderService.cancelOrder(orderId, userId);

        return ResponseEntity.ok(ApiResponse.success(order, "Order cancelled successfully"));
    }
}
