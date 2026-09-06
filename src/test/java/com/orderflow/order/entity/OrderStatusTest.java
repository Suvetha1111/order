package com.orderflow.order.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStatusTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING,    CONFIRMED,  true",
            "PENDING,    CANCELLED,  true",
            "PENDING,    FAILED,     true",
            "PENDING,    PROCESSING, false",
            "PENDING,    SHIPPED,    false",
            "CONFIRMED,  PROCESSING, true",
            "CONFIRMED,  CANCELLED,  true",
            "CONFIRMED,  FAILED,     true",
            "CONFIRMED,  PENDING,    false",
            "PROCESSING, SHIPPED,    true",
            "PROCESSING, FAILED,     true",
            "PROCESSING, CANCELLED,  false",
            "SHIPPED,    DELIVERED,  true",
            "SHIPPED,    FAILED,     true",
            "SHIPPED,    CANCELLED,  false",
            "DELIVERED,  CANCELLED,  false",
            "CANCELLED,  PENDING,    false",
            "FAILED,     PENDING,    false",
    })
    void testStateTransitions(OrderStatus from, OrderStatus to, boolean expected) {
        assertEquals(expected, from.canTransitionTo(to),
                "%s → %s should be %s".formatted(from, to, expected));
    }

    @Test
    void terminalStatesCannotTransition() {
        for (OrderStatus target : OrderStatus.values()) {
            assertFalse(OrderStatus.DELIVERED.canTransitionTo(target));
            assertFalse(OrderStatus.CANCELLED.canTransitionTo(target));
            assertFalse(OrderStatus.FAILED.canTransitionTo(target));
        }
    }

    @Test
    void cancellableStates() {
        assertTrue(OrderStatus.PENDING.isCancellable());
        assertTrue(OrderStatus.CONFIRMED.isCancellable());
        assertFalse(OrderStatus.PROCESSING.isCancellable());
        assertFalse(OrderStatus.SHIPPED.isCancellable());
        assertFalse(OrderStatus.DELIVERED.isCancellable());
    }
}
