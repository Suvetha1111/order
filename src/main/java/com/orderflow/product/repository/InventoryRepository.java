package com.orderflow.product.repository;

import com.orderflow.product.entity.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    Optional<Inventory> findByProductId(UUID productId);

    /**
     * Pessimistic write lock for inventory reservation.
     *
     * Why pessimistic here instead of optimistic?
     * During order placement, multiple concurrent requests for the same product
     * would all read the same available quantity, then all try to reserve.
     * With optimistic locking, all but one would fail with OptimisticLockException
     * and need retry logic. Pessimistic locking serializes the critical section,
     * giving each request a definitive answer on stock availability.
     *
     * This is used ONLY for the reserve/release operations during order processing.
     * Regular reads (product listing, stock checks) use the non-locking findByProductId.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.product.id = :productId")
    Optional<Inventory> findByProductIdForUpdate(@Param("productId") UUID productId);
}
