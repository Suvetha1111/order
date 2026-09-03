package com.orderflow.order.entity;

import java.util.Set;

/**
 * Order lifecycle state machine.
 *
 * Happy path:  PENDING → CONFIRMED → PROCESSING → SHIPPED → DELIVERED
 * Cancellation: PENDING or CONFIRMED → CANCELLED
 * Failure:     any pre-SHIPPED state → FAILED
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    FAILED;

    /**
     * Defines which transitions are valid from each state.
     */
    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING    -> Set.of(CONFIRMED, CANCELLED, FAILED).contains(target);
            case CONFIRMED  -> Set.of(PROCESSING, CANCELLED, FAILED).contains(target);
            case PROCESSING -> Set.of(SHIPPED, FAILED).contains(target);
            case SHIPPED    -> Set.of(DELIVERED, FAILED).contains(target);
            case DELIVERED, CANCELLED, FAILED -> false;  // terminal states
        };
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED || this == FAILED;
    }

    public boolean isCancellable() {
        return this == PENDING || this == CONFIRMED;
    }
}
