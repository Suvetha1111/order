package com.orderflow.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank(message = "Product name is required")
        @Size(max = 255, message = "Product name must be 255 characters or less")
        String name,

        @Size(max = 2000, message = "Description must be 2000 characters or less")
        String description,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.01", message = "Price must be greater than zero")
        BigDecimal price,

        @NotBlank(message = "SKU is required")
        @Size(max = 100, message = "SKU must be 100 characters or less")
        String sku,

        @NotNull(message = "Initial stock quantity is required")
        @Min(value = 0, message = "Initial stock cannot be negative")
        Integer initialStock
) {}
