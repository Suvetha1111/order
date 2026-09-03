package com.orderflow.product.dto;

import com.orderflow.product.entity.Inventory;
import com.orderflow.product.entity.Product;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Product response DTO. Implements Serializable for Redis caching.
 */
public record ProductResponse(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        String sku,
        boolean active,
        int availableStock,
        Instant createdAt,
        Instant updatedAt
) implements Serializable {

    public static ProductResponse from(Product product, Inventory inventory) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getSku(),
                product.isActive(),
                inventory != null ? inventory.getAvailable() : 0,
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
