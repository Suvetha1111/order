package com.orderflow.product.service;

import com.orderflow.product.dto.UpdateInventoryRequest;
import com.orderflow.product.entity.Inventory;
import com.orderflow.product.repository.InventoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Admin: set absolute inventory quantity for a product.
     * Uses pessimistic lock to prevent concurrent modifications.
     * Evicts the product cache since available stock changed.
     */
    @CacheEvict(value = "products", key = "#productId")
    @Transactional
    public void updateInventory(UUID productId, UpdateInventoryRequest request) {
        Inventory inventory = inventoryRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Inventory not found for product: " + productId));

        int newQuantity = request.quantity();

        // Ensure new quantity can cover existing reservations
        if (newQuantity < inventory.getReserved()) {
            throw new IllegalArgumentException(
                    "Cannot set quantity to %d: %d units are currently reserved"
                            .formatted(newQuantity, inventory.getReserved()));
        }

        inventory.setQuantity(newQuantity);
        inventoryRepository.save(inventory);

        log.info("Inventory updated: productId={}, newQuantity={}, reserved={}, available={}",
                productId, newQuantity, inventory.getReserved(), inventory.getAvailable());
    }

    /**
     * Reserve stock for an order. Called during order creation.
     * Uses pessimistic lock (findByProductIdForUpdate) to serialize
     * concurrent reservations and prevent overselling.
     */
    @CacheEvict(value = "products", key = "#productId")
    @Transactional
    public void reserveStock(UUID productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Inventory not found for product: " + productId));

        inventory.reserve(quantity);  // throws InsufficientInventoryException if not enough
        inventoryRepository.save(inventory);

        log.debug("Stock reserved: productId={}, quantity={}, remaining={}",
                productId, quantity, inventory.getAvailable());
    }

    /**
     * Release previously reserved stock (e.g., order cancelled or failed).
     */
    @CacheEvict(value = "products", key = "#productId")
    @Transactional
    public void releaseStock(UUID productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Inventory not found for product: " + productId));

        inventory.releaseReservation(quantity);
        inventoryRepository.save(inventory);

        log.debug("Stock released: productId={}, quantity={}, available={}",
                productId, quantity, inventory.getAvailable());
    }

    /**
     * Confirm shipment — deduct from both quantity and reserved.
     */
    @CacheEvict(value = "products", key = "#productId")
    @Transactional
    public void confirmShipment(UUID productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Inventory not found for product: " + productId));

        inventory.confirmShipment(quantity);
        inventoryRepository.save(inventory);

        log.debug("Shipment confirmed: productId={}, quantity={}, remaining={}",
                productId, quantity, inventory.getQuantity());
    }
}
