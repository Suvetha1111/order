package com.orderflow.order.service;

import com.orderflow.auth.entity.User;
import com.orderflow.auth.repository.UserRepository;
import com.orderflow.common.exception.DuplicateRequestException;
import com.orderflow.common.exception.InsufficientInventoryException;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UserRepository userRepository;
    @Mock private InventoryService inventoryService;
    @Mock private OutboxService outboxService;

    private OrderService orderService;

    private UUID userId;
    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        orderService = new OrderService(orderRepository, productRepository,
                userRepository, inventoryService, outboxService, meterRegistry);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setEmail("test@example.com");
        user.setFullName("Test User");

        product = new Product();
        product.setName("Test Product");
        product.setPrice(new BigDecimal("29.99"));
        product.setSku("TST-001");
        product.setActive(true);
    }

    @Nested
    @DisplayName("createOrder")
    class CreateOrder {

        @Test
        @DisplayName("should create order with valid request")
        void createOrder_success() {
            // Arrange
            String idempotencyKey = UUID.randomUUID().toString();
            var itemRequest = new OrderItemRequest(product.getId(), 2);
            var request = new CreateOrderRequest(List.of(itemRequest), idempotencyKey);

            when(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            OrderResponse response = orderService.createOrder(userId, request);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(response.items()).hasSize(1);

            // Verify inventory was reserved
            verify(inventoryService).reserveStock(product.getId(), 2);

            // Verify outbox event was written
            ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
            verify(outboxService).saveOrderEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().eventType()).isEqualTo(OrderEvent.ORDER_CREATED);
        }

        @Test
        @DisplayName("should return existing order for duplicate idempotency key from same user")
        void createOrder_duplicateIdempotencyKey_sameUser() {
            // Arrange
            String idempotencyKey = UUID.randomUUID().toString();
            var request = new CreateOrderRequest(
                    List.of(new OrderItemRequest(product.getId(), 1)), idempotencyKey);

            Order existingOrder = new Order();
            existingOrder.setId(UUID.randomUUID());
            existingOrder.setUser(user);
            existingOrder.setTotalAmount(new BigDecimal("29.99"));
            existingOrder.setIdempotencyKey(idempotencyKey);

            when(orderRepository.findByIdempotencyKey(idempotencyKey))
                    .thenReturn(Optional.of(existingOrder));
            when(orderRepository.findByIdWithItems(existingOrder.getId()))
                    .thenReturn(Optional.of(existingOrder));

            // Act — should return existing order, not create a new one
            OrderResponse response = orderService.createOrder(userId, request);

            // Assert — no new order created
            assertThat(response).isNotNull();
            verify(inventoryService, never()).reserveStock(any(), anyInt());
            verify(outboxService, never()).saveOrderEvent(any());
        }

        @Test
        @DisplayName("should throw when idempotency key used by different user")
        void createOrder_duplicateIdempotencyKey_differentUser() {
            // Arrange
            String idempotencyKey = UUID.randomUUID().toString();
            var request = new CreateOrderRequest(
                    List.of(new OrderItemRequest(product.getId(), 1)), idempotencyKey);

            User otherUser = new User();
            otherUser.setId(UUID.randomUUID());
            otherUser.setEmail("other@example.com");
            otherUser.setFullName("Other User");

            Order existingOrder = new Order();
            existingOrder.setUser(otherUser);
            existingOrder.setIdempotencyKey(idempotencyKey);

            when(orderRepository.findByIdempotencyKey(idempotencyKey))
                    .thenReturn(Optional.of(existingOrder));

            // Act & Assert
            assertThatThrownBy(() -> orderService.createOrder(userId, request))
                    .isInstanceOf(DuplicateRequestException.class)
                    .hasMessageContaining("Idempotency key already used");
        }

        @Test
        @DisplayName("should throw when product not found")
        void createOrder_productNotFound() {
            String idempotencyKey = UUID.randomUUID().toString();
            UUID missingProductId = UUID.randomUUID();
            var request = new CreateOrderRequest(
                    List.of(new OrderItemRequest(missingProductId, 1)), idempotencyKey);

            when(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(productRepository.findById(missingProductId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.createOrder(userId, request))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Product not found");
        }

        @Test
        @DisplayName("should throw when product is inactive")
        void createOrder_inactiveProduct() {
            String idempotencyKey = UUID.randomUUID().toString();
            product.setActive(false);
            var request = new CreateOrderRequest(
                    List.of(new OrderItemRequest(product.getId(), 1)), idempotencyKey);

            when(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

            assertThatThrownBy(() -> orderService.createOrder(userId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not available for ordering");
        }

        @Test
        @DisplayName("should throw when user not found")
        void createOrder_userNotFound() {
            String idempotencyKey = UUID.randomUUID().toString();
            var request = new CreateOrderRequest(
                    List.of(new OrderItemRequest(product.getId(), 1)), idempotencyKey);

            when(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.createOrder(userId, request))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("User not found");
        }

        @Test
        @DisplayName("should propagate insufficient inventory exception")
        void createOrder_insufficientInventory() {
            String idempotencyKey = UUID.randomUUID().toString();
            var request = new CreateOrderRequest(
                    List.of(new OrderItemRequest(product.getId(), 100)), idempotencyKey);

            when(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
            doThrow(new InsufficientInventoryException("Test Product", 100, 5))
                    .when(inventoryService).reserveStock(product.getId(), 100);

            assertThatThrownBy(() -> orderService.createOrder(userId, request))
                    .isInstanceOf(InsufficientInventoryException.class);

            // Verify no order was saved
            verify(orderRepository, never()).save(any());
            verify(outboxService, never()).saveOrderEvent(any());
        }
    }

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrder {

        @Test
        @DisplayName("should cancel a pending order and release inventory")
        void cancelOrder_success() {
            UUID orderId = UUID.randomUUID();
            Order order = new Order();
            order.setUser(user);
            order.setTotalAmount(new BigDecimal("59.98"));
            order.setIdempotencyKey("key-1");

            OrderItem item = new OrderItem(product, 2, product.getPrice());
            order.addItem(item);

            when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            OrderResponse response = orderService.cancelOrder(orderId, userId);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
            verify(inventoryService).releaseStock(product.getId(), 2);

            // Verify cancellation event was written
            ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
            verify(outboxService).saveOrderEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().eventType()).isEqualTo(OrderEvent.ORDER_CANCELLED);
        }

        @Test
        @DisplayName("should throw when order not found")
        void cancelOrder_notFound() {
            UUID orderId = UUID.randomUUID();

            when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.cancelOrder(orderId, userId))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Order not found");
        }
    }

    @Nested
    @DisplayName("transitionOrder")
    class TransitionOrder {

        @Test
        @DisplayName("should transition order and write outbox event")
        void transitionOrder_toConfirmed() {
            UUID orderId = UUID.randomUUID();
            Order order = new Order();
            order.setUser(user);
            order.setTotalAmount(new BigDecimal("29.99"));
            order.setIdempotencyKey("key-1");

            when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            orderService.transitionOrder(orderId, OrderStatus.CONFIRMED);

            // Assert
            verify(orderRepository).save(order);
            ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
            verify(outboxService).saveOrderEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().eventType()).isEqualTo(OrderEvent.ORDER_CONFIRMED);
        }

        @Test
        @DisplayName("should not write outbox event for unmapped status")
        void transitionOrder_pendingStatus_noEvent() {
            UUID orderId = UUID.randomUUID();
            Order order = new Order();
            order.setUser(user);
            order.setTotalAmount(new BigDecimal("29.99"));
            order.setIdempotencyKey("key-1");
            // Order starts as PENDING, transitioning to PENDING isn't valid
            // but PROCESSING doesn't have an event mapped in the switch
            // First move to CONFIRMED, then PROCESSING
            order.transitionTo(OrderStatus.CONFIRMED);

            when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            orderService.transitionOrder(orderId, OrderStatus.PROCESSING);

            verify(orderRepository).save(order);
            // PROCESSING has no event type mapped → no outbox event
            verify(outboxService, never()).saveOrderEvent(any());
        }

        @Test
        @DisplayName("should throw when order not found for transition")
        void transitionOrder_notFound() {
            UUID orderId = UUID.randomUUID();

            when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.transitionOrder(orderId, OrderStatus.CONFIRMED))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getOrder")
    class GetOrder {

        @Test
        @DisplayName("should throw when order not found")
        void getOrder_notFound() {
            UUID orderId = UUID.randomUUID();

            when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.getOrder(orderId, userId))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }
}
