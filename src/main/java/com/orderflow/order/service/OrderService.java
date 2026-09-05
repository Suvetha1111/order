package com.orderflow.order.service;

import com.orderflow.auth.entity.User;
import com.orderflow.auth.repository.UserRepository;
import com.orderflow.common.dto.PagedResponse;
import com.orderflow.common.exception.DuplicateRequestException;
import com.orderflow.event.dto.OrderEvent;
import com.orderflow.event.service.OutboxService;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.OrderItemRequest;
import com.orderflow.order.dto.OrderResponse;
import com.orderflow.order.entity.Order;
import com.orderflow.order.entity.OrderItem;
import com.orderflow.order.entity.OrderStatus;
import com.orderflow.order.repository.OrderRepository;
import com.orderflow.product.entity.Product;
import com.orderflow.product.repository.ProductRepository;
import com.orderflow.product.service.InventoryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Core order management service.
 *
 * Order creation flow (single transaction):
 *   1. Check idempotency key — return existing order if duplicate
 *   2. Validate all products exist and are active
 *   3. Reserve inventory for each item (pessimistic lock)
 *   4. Create order + order items
 *   5. Write ORDER_CREATED event to outbox
 *   6. COMMIT — all of the above is atomic
 *
 * The outbox event is published to Kafka by OutboxPublisher (Phase 5),
 * which triggers downstream consumers for inventory confirmation,
 * payment simulation, and notifications.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final OutboxService outboxService;
    private final Timer orderCreationTimer;
    private final Timer orderCancellationTimer;
    private final Counter ordersCreatedCounter;
    private final Counter ordersCancelledCounter;

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        UserRepository userRepository,
                        InventoryService inventoryService,
                        OutboxService outboxService,
                        MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.inventoryService = inventoryService;
        this.outboxService = outboxService;
        this.orderCreationTimer = meterRegistry.timer("order.creation.time");
        this.orderCancellationTimer = meterRegistry.timer("order.cancellation.time");
        this.ordersCreatedCounter = Counter.builder("orders.created.total")
                .description("Total number of orders created")
                .register(meterRegistry);
        this.ordersCancelledCounter = Counter.builder("orders.cancelled.total")
                .description("Total number of orders cancelled")
                .register(meterRegistry);
    }

    /**
     * Create a new order. Idempotent — duplicate requests with the same
     * idempotency key return the original order.
     */
    @Transactional
    public OrderResponse createOrder(UUID userId, CreateOrderRequest request) {
        return orderCreationTimer.record(() -> doCreateOrder(userId, request));
    }

    private OrderResponse doCreateOrder(UUID userId, CreateOrderRequest request) {
        // ── 1. Idempotency check ──────────────────────────────────
        Optional<Order> existing = orderRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            Order existingOrder = existing.get();
            if (!existingOrder.getUser().getId().equals(userId)) {
                throw new DuplicateRequestException("Idempotency key already used");
            }
            log.info("Duplicate order request detected: idempotencyKey={}, orderId={}",
                    request.idempotencyKey(), existingOrder.getId());
            return loadOrderResponse(existingOrder.getId());
        }

        // ── 2. Load user ──────────────────────────────────────────
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        // ── 3. Validate products & reserve inventory ──────────────
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : request.items()) {
            Product product = productRepository.findById(itemRequest.productId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Product not found: " + itemRequest.productId()));

            if (!product.isActive()) {
                throw new IllegalArgumentException(
                        "Product '%s' is not available for ordering".formatted(product.getName()));
            }

            // Reserve inventory (pessimistic lock inside)
            inventoryService.reserveStock(product.getId(), itemRequest.quantity());

            OrderItem item = new OrderItem(product, itemRequest.quantity(), product.getPrice());
            orderItems.add(item);

            totalAmount = totalAmount.add(item.getSubtotal());
        }

        // ── 4. Create order ───────────────────────────────────────
        Order order = new Order();
        order.setUser(user);
        order.setTotalAmount(totalAmount);
        order.setIdempotencyKey(request.idempotencyKey());

        for (OrderItem item : orderItems) {
            order.addItem(item);
        }

        order = orderRepository.save(order);

        // ── 5. Write outbox event (same transaction) ──────────────
        OrderEvent event = buildOrderEvent(order, OrderEvent.ORDER_CREATED);
        outboxService.saveOrderEvent(event);

        ordersCreatedCounter.increment();
        log.info("Order created: orderId={}, userId={}, totalAmount={}, items={}",
                order.getId(), userId, totalAmount, orderItems.size());

        return OrderResponse.from(order);
    }

    /**
     * Cancel an order. Only PENDING and CONFIRMED orders can be cancelled.
     */
    @Transactional
    public OrderResponse cancelOrder(UUID orderId, UUID userId) {
        return orderCancellationTimer.record(() -> doCancelOrder(orderId, userId));
    }

    private OrderResponse doCancelOrder(UUID orderId, UUID userId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));

        // Only the order owner can cancel
        if (!order.getUser().getId().equals(userId)) {
            throw new EntityNotFoundException("Order not found: " + orderId);
        }

        if (!order.getStatus().isCancellable()) {
            throw new IllegalStateException(
                    "Order cannot be cancelled in status: " + order.getStatus());
        }

        // Release reserved inventory
        for (OrderItem item : order.getItems()) {
            inventoryService.releaseStock(item.getProduct().getId(), item.getQuantity());
        }

        order.transitionTo(OrderStatus.CANCELLED);
        orderRepository.save(order);

        // Write cancellation event to outbox
        OrderEvent event = buildOrderEvent(order, OrderEvent.ORDER_CANCELLED);
        outboxService.saveOrderEvent(event);

        ordersCancelledCounter.increment();
        log.info("Order cancelled: orderId={}, userId={}", orderId, userId);

        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId, UUID userId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));

        // Users can only see their own orders (admins bypass via controller)
        if (!order.getUser().getId().equals(userId)) {
            throw new EntityNotFoundException("Order not found: " + orderId);
        }

        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PagedResponse<OrderResponse> listUserOrders(UUID userId, Pageable pageable) {
        Page<OrderResponse> page = orderRepository.findByUserId(userId, pageable)
                .map(order -> {
                    // Eagerly load items for response mapping
                    Order loaded = orderRepository.findByIdWithItems(order.getId())
                            .orElseThrow();
                    return OrderResponse.from(loaded);
                });

        return PagedResponse.from(page);
    }

    /**
     * Transition an order to a new status. Called by event consumers.
     */
    @Transactional
    public void transitionOrder(UUID orderId, OrderStatus newStatus) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));

        order.transitionTo(newStatus);
        orderRepository.save(order);

        String eventType = switch (newStatus) {
            case CONFIRMED -> OrderEvent.ORDER_CONFIRMED;
            case SHIPPED -> OrderEvent.ORDER_SHIPPED;
            case DELIVERED -> OrderEvent.ORDER_DELIVERED;
            case FAILED -> OrderEvent.ORDER_FAILED;
            default -> null;
        };

        if (eventType != null) {
            outboxService.saveOrderEvent(buildOrderEvent(order, eventType));
        }

        log.info("Order transitioned: orderId={}, newStatus={}", orderId, newStatus);
    }

    private OrderResponse loadOrderResponse(UUID orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));
        return OrderResponse.from(order);
    }

    private OrderEvent buildOrderEvent(Order order, String eventType) {
        List<OrderEvent.OrderEventItem> eventItems = order.getItems().stream()
                .map(item -> new OrderEvent.OrderEventItem(
                        item.getProduct().getId(),
                        item.getProduct().getName(),
                        item.getQuantity(),
                        item.getUnitPrice()
                ))
                .toList();

        return new OrderEvent(
                UUID.randomUUID(),  // unique event ID
                eventType,
                order.getId(),
                order.getUser().getId(),
                Instant.now(),
                1,  // event schema version
                order.getTotalAmount(),
                order.getStatus().name(),
                eventItems
        );
    }
}
