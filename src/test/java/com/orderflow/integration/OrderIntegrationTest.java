package com.orderflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.auth.dto.LoginRequest;
import com.orderflow.auth.dto.RegisterRequest;
import com.orderflow.auth.entity.Role;
import com.orderflow.auth.entity.User;
import com.orderflow.auth.repository.RoleRepository;
import com.orderflow.auth.repository.UserRepository;
import com.orderflow.event.repository.OutboxEventRepository;
import com.orderflow.order.entity.OrderStatus;
import com.orderflow.order.repository.OrderRepository;
import com.orderflow.product.entity.Inventory;
import com.orderflow.product.entity.Product;
import com.orderflow.product.repository.InventoryRepository;
import com.orderflow.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the order lifecycle.
 *
 * Tests the full flow with real PostgreSQL, Redis, and Kafka:
 * - Order creation with inventory reservation
 * - Idempotent order creation
 * - Order cancellation with inventory release
 * - Authorization (users can only see their own orders)
 * - Outbox event generation
 */
class OrderIntegrationTest extends BaseIntegrationTest {

    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;

    private String authToken;
    private UUID productId;

    @BeforeEach
    void setUp() throws Exception {
        // Clean slate
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        // Ensure CUSTOMER role exists
        if (roleRepository.findByName("CUSTOMER").isEmpty()) {
            Role role = new Role();
            role.setName("CUSTOMER");
            roleRepository.save(role);
        }

        // Register a user
        var registerReq = new RegisterRequest("Test User", "test@example.com", "password123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated());

        // Login to get token from Authorization header
        var loginReq = new LoginRequest("test@example.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        authToken = loginResult.getResponse().getHeader("Authorization").replace("Bearer ", "");

        // Create a product with inventory
        Product product = new Product();
        product.setName("Integration Test Widget");
        product.setPrice(new BigDecimal("25.00"));
        product.setSku("INT-TEST-001");
        product.setActive(true);
        product = productRepository.save(product);
        productId = product.getId();

        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        inventory.setQuantity(100);
        inventory.setReserved(0);
        inventoryRepository.save(inventory);
    }

    @Test
    @DisplayName("POST /api/orders — should create order and write outbox event")
    void createOrder_success() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                    "items": [{"productId": "%s", "quantity": 3}],
                    "idempotencyKey": "%s"
                }
                """.formatted(productId, idempotencyKey);

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.totalAmount").value(75.00))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(25.00));

        // Verify inventory was reserved
        Inventory inv = inventoryRepository.findByProductId(productId).orElseThrow();
        assertThat(inv.getReserved()).isEqualTo(3);
        assertThat(inv.getQuantity()).isEqualTo(100);

        // Verify outbox event was created
        assertThat(outboxEventRepository.findUnpublishedBatch(10)).hasSize(1);
    }

    @Test
    @DisplayName("POST /api/orders — duplicate idempotency key returns same order")
    void createOrder_idempotent() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                    "items": [{"productId": "%s", "quantity": 2}],
                    "idempotencyKey": "%s"
                }
                """.formatted(productId, idempotencyKey);

        // First request
        MvcResult first = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        // Second request with same key
        MvcResult second = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andReturn();

        // Should return same order ID
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());

        assertThat(secondBody.get("data").get("id").asText())
                .isEqualTo(firstBody.get("data").get("id").asText());

        // Inventory should only be reserved once
        Inventory inv = inventoryRepository.findByProductId(productId).orElseThrow();
        assertThat(inv.getReserved()).isEqualTo(2);
    }

    @Test
    @DisplayName("POST /api/orders — insufficient inventory returns 422")
    void createOrder_insufficientInventory() throws Exception {
        String requestBody = """
                {
                    "items": [{"productId": "%s", "quantity": 999}],
                    "idempotencyKey": "%s"
                }
                """.formatted(productId, UUID.randomUUID());

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnprocessableEntity());

        // No inventory should be reserved
        Inventory inv = inventoryRepository.findByProductId(productId).orElseThrow();
        assertThat(inv.getReserved()).isEqualTo(0);
    }

    @Test
    @DisplayName("POST /api/orders/{id}/cancel — should cancel and release inventory")
    void cancelOrder_success() throws Exception {
        // Create order first
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                    "items": [{"productId": "%s", "quantity": 5}],
                    "idempotencyKey": "%s"
                }
                """.formatted(productId, idempotencyKey);

        MvcResult createResult = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createBody = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String orderId = createBody.get("data").get("id").asText();

        // Cancel
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // Verify inventory was released
        Inventory inv = inventoryRepository.findByProductId(productId).orElseThrow();
        assertThat(inv.getReserved()).isEqualTo(0);

        // Verify two outbox events: ORDER_CREATED + ORDER_CANCELLED
        assertThat(outboxEventRepository.findUnpublishedBatch(10)).hasSize(2);
    }

    @Test
    @DisplayName("GET /api/orders — should list user's orders")
    void listOrders_success() throws Exception {
        // Create two orders
        for (int i = 0; i < 2; i++) {
            String requestBody = """
                    {
                        "items": [{"productId": "%s", "quantity": 1}],
                        "idempotencyKey": "%s"
                    }
                    """.formatted(productId, UUID.randomUUID());

            mockMvc.perform(post("/api/orders")
                            .header("Authorization", "Bearer " + authToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("GET /api/orders/{id} — should return single order")
    void getOrder_success() throws Exception {
        String requestBody = """
                {
                    "items": [{"productId": "%s", "quantity": 1}],
                    "idempotencyKey": "%s"
                }
                """.formatted(productId, UUID.randomUUID());

        MvcResult createResult = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createBody = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String orderId = createBody.get("data").get("id").asText();

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId))
                .andExpect(jsonPath("$.data.items", hasSize(1)));
    }

    @Test
    @DisplayName("GET /api/orders/{id} — other user's order returns 404")
    void getOrder_otherUser() throws Exception {
        // Create order as test user
        String requestBody = """
                {
                    "items": [{"productId": "%s", "quantity": 1}],
                    "idempotencyKey": "%s"
                }
                """.formatted(productId, UUID.randomUUID());

        MvcResult createResult = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createBody = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String orderId = createBody.get("data").get("id").asText();

        // Register a different user and login to get token
        var otherReq = new RegisterRequest("Other User", "other@example.com", "password123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherReq)))
                .andExpect(status().isCreated());

        var otherLoginReq = new LoginRequest("other@example.com", "password123");
        MvcResult otherLoginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherLoginReq)))
                .andExpect(status().isOk())
                .andReturn();

        String otherToken = otherLoginResult.getResponse().getHeader("Authorization").replace("Bearer ", "");

        // Try to access the order as the other user
        mockMvc.perform(get("/api/orders/" + orderId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/orders — unauthenticated returns 401")
    void createOrder_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/orders — inactive product returns 400")
    void createOrder_inactiveProduct() throws Exception {
        // Deactivate the product
        Product product = productRepository.findById(productId).orElseThrow();
        product.setActive(false);
        productRepository.save(product);

        String requestBody = """
                {
                    "items": [{"productId": "%s", "quantity": 1}],
                    "idempotencyKey": "%s"
                }
                """.formatted(productId, UUID.randomUUID());

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }
}
