package com.orderflow.product.entity;

import com.orderflow.common.entity.BaseEntity;
import com.orderflow.common.exception.InsufficientInventoryException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Inventory tracks available quantity and reserved quantity for a product.
 *
 * 'quantity' = total stock on hand
 * 'reserved' = stock reserved by pending orders but not yet shipped
 * Available for new orders = quantity - reserved
 *
 * Uses optimistic locking via @Version in BaseEntity to prevent
 * lost-update problems under concurrent order placement.
 */
@Entity
@Table(name = "inventory")
public class Inventory extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int reserved;

    public Inventory() {}

    public Inventory(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
        this.reserved = 0;
    }

    /**
     * Available quantity = total stock minus what's already reserved.
     */
    public int getAvailable() {
        return quantity - reserved;
    }

    /**
     * Reserve stock for a new order. Throws if insufficient.
     */
    public void reserve(int amount) {
        if (amount > getAvailable()) {
            throw new InsufficientInventoryException(
                    product.getName(), amount, getAvailable());
        }
        this.reserved += amount;
    }

    /**
     * Release previously reserved stock (e.g., order cancelled).
     */
    public void releaseReservation(int amount) {
        this.reserved = Math.max(0, this.reserved - amount);
    }

    /**
     * Confirm reserved stock has shipped — reduce both quantity and reserved.
     */
    public void confirmShipment(int amount) {
        this.quantity -= amount;
        this.reserved -= amount;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getReserved() {
        return reserved;
    }

    public void setReserved(int reserved) {
        this.reserved = reserved;
    }
}
