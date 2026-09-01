package com.orderflow.common.exception;

public class InsufficientInventoryException extends RuntimeException {

    public InsufficientInventoryException(String productName, int requested, int available) {
        super("Insufficient inventory for '%s': requested %d, available %d"
                .formatted(productName, requested, available));
    }
}
