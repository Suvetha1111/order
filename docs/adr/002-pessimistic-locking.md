# ADR-002: Pessimistic Locking for Inventory Reservation

## Status
Accepted

## Context
When multiple users place orders concurrently for the same product, we need to ensure inventory is reserved correctly — no overselling, no lost updates. Two main approaches exist: optimistic locking (version-based conflict detection) and pessimistic locking (`SELECT FOR UPDATE`).

## Decision
We use **pessimistic locking** (`@Lock(PESSIMISTIC_WRITE)`) on the `InventoryRepository.findByProductIdForUpdate()` method.

When `OrderService.createOrder()` reserves inventory:
1. The inventory row is locked with `SELECT FOR UPDATE`
2. Available stock is checked (`quantity - reserved >= requestedAmount`)
3. The `reserved` field is incremented
4. The lock is released when the transaction commits

## Consequences

**Pros:**
- Concurrent requests are serialized at the DB level — no application-level retry logic needed
- Guarantees correctness: it's impossible to oversell because only one transaction can modify a product's inventory at a time
- Simple to implement and reason about
- Works well for the "hot product" scenario where many orders hit the same inventory row

**Cons:**
- Reduced throughput under very high contention (requests queue on the row lock)
- Risk of deadlocks if lock ordering isn't consistent (mitigated by always locking inventory rows in a predictable order within a transaction)
- Lock held for the duration of the transaction (we keep transactions short)

**Why not optimistic locking?**
- With optimistic locking (`@Version`), all-but-one concurrent requests would get `OptimisticLockException` and need retry logic
- Under high contention (flash sales), the retry rate becomes very high, wasting resources
- The retry logic adds complexity and can cascade into timeout issues
- We do use `@Version` on `BaseEntity` for other entities as a safety net, but inventory reservation specifically benefits from pessimistic locking

**Two-field inventory model:**
We track both `quantity` (total stock) and `reserved` (stock claimed by pending orders). This enables:
- `reserve()`: increment `reserved` (order placed)
- `releaseReservation()`: decrement `reserved` (order cancelled or payment failed)
- `confirmShipment()`: decrement both `quantity` and `reserved` (payment confirmed)

This avoids needing complex rollback logic — a failed payment simply releases the reservation.
