package com.orderflow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.auth.dto.RegisterRequest;
import com.orderflow.auth.entity.Role;
import com.orderflow.auth.entity.User;
import com.orderflow.auth.repository.RoleRepository;
import com.orderflow.auth.repository.UserRepository;
import com.orderflow.product.repository.InventoryRepository;
import com.orderflow.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for product and inventory management.
 *
 * Tests admin-only product CRUD, public product listing,
 * and inventory management with real PostgreSQL and Redis.
 */
class ProductIntegrationTest extends BaseIntegrationTest {

    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private InventoryRepository inventoryRepository;

    private String adminToken;
    private String customerToken;

    @BeforeEach
    void setUp() throws Exception {
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        // Ensure roles exist
        if (roleRepository.findByName("CUSTOMER").isEmpty()) {
            Role role = new Role();
            role.setName("CUSTOMER");
            roleRepository.save(role);
        }
        if (roleRepository.findByName("ADMIN").isEmpty()) {
            Role role = new Role();
            role.setName("ADMIN");
            roleRepository.save(role);
        }

        // Register a customer and login to get token from header
        var customerReq = new RegisterRequest("Customer", "customer@example.com", "password123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(customerReq)))
                .andExpect(status().isCreated());

        var customerLoginReq = new com.orderflow.auth.dto.LoginRequest("customer@example.com", "password123");
        MvcResult customerLoginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(customerLoginReq)))
                .andExpect(status().isOk())
                .andReturn();
        customerToken = customerLoginResult.getResponse().getHeader("Authorization").replace("Bearer ", "");

        // Create an admin user manually (register gives CUSTOMER role)
        User admin = userRepository.findByEmail("customer@example.com").orElseThrow();
        // We need a separate admin user
        var adminReq = new RegisterRequest("Admin", "admin@example.com", "password123");
        MvcResult adminResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminReq)))
                .andExpect(status().isCreated())
                .andReturn();

        // Add ADMIN role to admin user
        User adminUser = userRepository.findByEmail("admin@example.com").orElseThrow();
        Role adminRole = roleRepository.findByName("ADMIN").orElseThrow();
        adminUser.getRoles().add(adminRole);
        userRepository.save(adminUser);

        // Re-login to get token with updated roles from Authorization header
        var loginReq = new com.orderflow.auth.dto.LoginRequest("admin@example.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        adminToken = loginResult.getResponse().getHeader("Authorization").replace("Bearer ", "");
    }

    @Test
    @DisplayName("POST /api/products — admin can create product")
    void createProduct_admin() throws Exception {
        String requestBody = """
                {
                    "name": "Super Widget",
                    "description": "A very nice widget",
                    "price": 49.99,
                    "sku": "SW-001",
                    "initialStock": 200
                }
                """;

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Super Widget"))
                .andExpect(jsonPath("$.data.sku").value("SW-001"))
                .andExpect(jsonPath("$.data.availableStock").value(200));
    }

    @Test
    @DisplayName("POST /api/products — customer gets 403")
    void createProduct_customerForbidden() throws Exception {
        String requestBody = """
                {
                    "name": "Widget",
                    "description": "desc",
                    "price": 10.00,
                    "sku": "W-001",
                    "initialStock": 50
                }
                """;

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/products — public access, paginated")
    void listProducts_public() throws Exception {
        // Create a product as admin
        String requestBody = """
                {
                    "name": "Public Widget",
                    "description": "Anyone can see this",
                    "price": 19.99,
                    "sku": "PW-001",
                    "initialStock": 100
                }
                """;
        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        // List without auth
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].name").value("Public Widget"));
    }

    @Test
    @DisplayName("GET /api/products/{id} — public access")
    void getProduct_public() throws Exception {
        // Create
        String requestBody = """
                {
                    "name": "Single Widget",
                    "description": "Fetch me",
                    "price": 9.99,
                    "sku": "SG-001",
                    "initialStock": 50
                }
                """;
        MvcResult createResult = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String productId = body.get("data").get("id").asText();

        // Fetch without auth
        mockMvc.perform(get("/api/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Single Widget"))
                .andExpect(jsonPath("$.data.availableStock").value(50));
    }

    @Test
    @DisplayName("PUT /api/products/{id} — admin can update product")
    void updateProduct_admin() throws Exception {
        // Create
        String createBody = """
                {
                    "name": "Old Name",
                    "description": "desc",
                    "price": 10.00,
                    "sku": "UP-001",
                    "initialStock": 30
                }
                """;
        MvcResult createResult = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String productId = body.get("data").get("id").asText();

        // Update
        String updateBody = """
                {"name": "New Name", "price": 15.00}
                """;
        mockMvc.perform(put("/api/products/" + productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Name"))
                .andExpect(jsonPath("$.data.price").value(15.00));
    }

    @Test
    @DisplayName("PUT /api/products/{id}/inventory — admin can update stock")
    void updateInventory_admin() throws Exception {
        // Create product
        String createBody = """
                {
                    "name": "Stock Widget",
                    "description": "desc",
                    "price": 5.00,
                    "sku": "STK-001",
                    "initialStock": 10
                }
                """;
        MvcResult createResult = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String productId = body.get("data").get("id").asText();

        // Update inventory
        mockMvc.perform(put("/api/products/" + productId + "/inventory")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableStock").value(500));
    }
}
