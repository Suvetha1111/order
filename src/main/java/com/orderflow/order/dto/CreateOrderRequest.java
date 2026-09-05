package com.orderflow.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateOrderRequest(
        @NotEmpty(message = "Order must contain at least one item")
        @Valid
        List<OrderItemRequest> items,

        /**
         * Client-generated idempotency key to prevent duplicate orders.
         * If the same key is submitted twice, the second request returns
         * the existing order instead of creating a new one.
         */
        @NotNull(message = "Idempotency key is required")
        String idempotencyKey
) {}
