package com.orderflow.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateProductRequest(
        @Size(max = 255, message = "Product name must be 255 characters or less")
        String name,

        @Size(max = 2000, message = "Description must be 2000 characters or less")
        String description,

        @DecimalMin(value = "0.01", message = "Price must be greater than zero")
        BigDecimal price,

        Boolean active
) {}
