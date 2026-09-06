package com.orderflow.product.service;

import com.orderflow.common.exception.InsufficientInventoryException;
import com.orderflow.product.dto.UpdateInventoryRequest;
import com.orderflow.product.entity.Inventory;
import com.orderflow.product.entity.Product;
import com.orderflow.product.repository.InventoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock private InventoryRepository inventoryRepository;

    private InventoryService inventoryService;
    private Product testProduct;
    private UUID productId;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(inventoryRepository);
        productId = UUID.randomUUID();
        testProduct = new Product();
        testProduct.setId(productId);
        testProduct.setName("Test Product");
        testProduct.setPrice(new BigDecimal("10.00"));
    }

    @Test
    void reserveStock_success() {
        Inventory inventory = new Inventory(testProduct, 100);
        when(inventoryRepository.findByProductIdForUpdate(productId))
                .thenReturn(Optional.of(inventory));

        inventoryService.reserveStock(productId, 5);

        assertEquals(5, inventory.getReserved());
        assertEquals(95, inventory.getAvailable());
        verify(inventoryRepository).save(inventory);
    }

    @Test
    void reserveStock_insufficientInventory_throws() {
        Inventory inventory = new Inventory(testProduct, 3);
        when(inventoryRepository.findByProductIdForUpdate(productId))
                .thenReturn(Optional.of(inventory));

        assertThrows(InsufficientInventoryException.class,
                () -> inventoryService.reserveStock(productId, 5));
    }

    @Test
    void reserveStock_respectsExistingReservations() {
        Inventory inventory = new Inventory(testProduct, 10);
        inventory.setReserved(8);  // only 2 available
        when(inventoryRepository.findByProductIdForUpdate(productId))
                .thenReturn(Optional.of(inventory));

        assertThrows(InsufficientInventoryException.class,
                () -> inventoryService.reserveStock(productId, 3));
    }

    @Test
    void releaseStock_success() {
        Inventory inventory = new Inventory(testProduct, 100);
        inventory.setReserved(10);
        when(inventoryRepository.findByProductIdForUpdate(productId))
                .thenReturn(Optional.of(inventory));

        inventoryService.releaseStock(productId, 5);

        assertEquals(5, inventory.getReserved());
        assertEquals(95, inventory.getAvailable());
    }

    @Test
    void releaseStock_doesNotGoNegative() {
        Inventory inventory = new Inventory(testProduct, 100);
        inventory.setReserved(3);
        when(inventoryRepository.findByProductIdForUpdate(productId))
                .thenReturn(Optional.of(inventory));

        inventoryService.releaseStock(productId, 10);  // release more than reserved

        assertEquals(0, inventory.getReserved());
    }

    @Test
    void confirmShipment_deductsBoth() {
        Inventory inventory = new Inventory(testProduct, 100);
        inventory.setReserved(10);
        when(inventoryRepository.findByProductIdForUpdate(productId))
                .thenReturn(Optional.of(inventory));

        inventoryService.confirmShipment(productId, 5);

        assertEquals(95, inventory.getQuantity());
        assertEquals(5, inventory.getReserved());
    }

    @Test
    void updateInventory_success() {
        Inventory inventory = new Inventory(testProduct, 100);
        inventory.setReserved(5);
        when(inventoryRepository.findByProductIdForUpdate(productId))
                .thenReturn(Optional.of(inventory));

        inventoryService.updateInventory(productId, new UpdateInventoryRequest(50));

        assertEquals(50, inventory.getQuantity());
    }

    @Test
    void updateInventory_belowReserved_throws() {
        Inventory inventory = new Inventory(testProduct, 100);
        inventory.setReserved(20);
        when(inventoryRepository.findByProductIdForUpdate(productId))
                .thenReturn(Optional.of(inventory));

        assertThrows(IllegalArgumentException.class,
                () -> inventoryService.updateInventory(productId, new UpdateInventoryRequest(10)));
    }

    @Test
    void reserveStock_productNotFound_throws() {
        when(inventoryRepository.findByProductIdForUpdate(productId))
                .thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> inventoryService.reserveStock(productId, 5));
    }
}
