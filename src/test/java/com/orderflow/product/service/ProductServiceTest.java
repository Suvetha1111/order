package com.orderflow.product.service;

import com.orderflow.common.dto.PagedResponse;
import com.orderflow.product.dto.CreateProductRequest;
import com.orderflow.product.dto.ProductResponse;
import com.orderflow.product.dto.UpdateProductRequest;
import com.orderflow.product.entity.Inventory;
import com.orderflow.product.entity.Product;
import com.orderflow.product.repository.InventoryRepository;
import com.orderflow.product.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private InventoryRepository inventoryRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, inventoryRepository);
    }

    @Test
    void createProduct_success() {
        CreateProductRequest request = new CreateProductRequest(
                "Widget", "A test widget", new BigDecimal("29.99"), "WDG-001", 100);

        when(productRepository.existsBySku("WDG-001")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(inv -> {
            Inventory i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        ProductResponse response = productService.createProduct(request);

        assertNotNull(response.id());
        assertEquals("Widget", response.name());
        assertEquals(new BigDecimal("29.99"), response.price());
        assertEquals(100, response.availableStock());

        // Verify SKU is uppercased
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertEquals("WDG-001", captor.getValue().getSku());
    }

    @Test
    void createProduct_duplicateSku_throws() {
        CreateProductRequest request = new CreateProductRequest(
                "Widget", null, new BigDecimal("29.99"), "WDG-001", 100);
        when(productRepository.existsBySku("WDG-001")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> productService.createProduct(request));
    }

    @Test
    void getProduct_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> productService.getProduct(id));
    }

    @Test
    void updateProduct_partialUpdate() {
        UUID productId = UUID.randomUUID();
        Product existing = new Product();
        existing.setId(productId);
        existing.setName("Old Name");
        existing.setPrice(new BigDecimal("10.00"));
        existing.setActive(true);

        when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.empty());

        // Only update name, leave other fields null
        UpdateProductRequest request = new UpdateProductRequest("New Name", null, null, null);

        ProductResponse response = productService.updateProduct(productId, request);

        assertEquals("New Name", response.name());
        assertEquals(new BigDecimal("10.00"), response.price());  // unchanged
    }

    @Test
    void listProducts_returnsPaginated() {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName("Widget");
        product.setPrice(new BigDecimal("9.99"));
        product.setSku("WDG-001");

        Page<Product> page = new PageImpl<>(List.of(product));
        Pageable pageable = PageRequest.of(0, 20);

        when(productRepository.findByActiveTrue(pageable)).thenReturn(page);
        when(inventoryRepository.findByProductId(product.getId())).thenReturn(Optional.empty());

        PagedResponse<ProductResponse> response = productService.listProducts(pageable);

        assertEquals(1, response.content().size());
        assertEquals(1, response.totalElements());
    }
}
