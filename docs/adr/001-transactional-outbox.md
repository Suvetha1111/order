# ADR-001: Transactional Outbox Pattern

## Status
Accepted

## Context
When an order is created, we need to both persist the order in PostgreSQL and notify downstream services (payment, inventory confirmation, notifications) via Kafka. The naive approach — save to DB then publish to Kafka — has a dual-write problem: if the app crashes between the two operations, the data is inconsistent. Either the order exists without an event (consumers never learn about it) or the event is sent but the order doesn't exist (consumers process a phantom order).

## Decision
We use the **transactional outbox pattern**:

1. The `OrderService` saves both the order and an `OutboxEvent` in the **same database transaction**.
2. A scheduled `OutboxPublisher` polls the `outbox_events` table for unpublished events and sends them to Kafka.
3. After successful Kafka delivery, the event is marked as `published = true`.

The outbox event carries a pre-serialized JSON payload (the "fat event"), so the publisher simply relays it without needing to reconstruct the event from the order state.

## Consequences

**Pros:**
- Atomicity: the event is guaranteed to exist if and only if the order exists
- No distributed transaction (2PC) required between PostgreSQL and Kafka
- At-least-once delivery: if the publisher crashes after sending but before marking published, the event is re-sent on the next poll — consumers handle this via idempotent processing
- Ordering: events for the same order go to the same Kafka partition (keyed by `aggregateId`)
- Debuggable: unpublished events are visible in the database; the outbox table acts as an audit log

**Cons:**
- Slight delay between order creation and event delivery (polling interval, default 1s)
- Additional DB writes and polling load (mitigated by batch polling with LIMIT)
- At-least-once means consumers must be idempotent (we enforce this via `processed_events` table)

**Alternatives considered:**
- **Direct Kafka publish in transaction**: not possible — Kafka is not part of the DB transaction
- **CDC with Debezium**: production-grade but adds operational complexity (Kafka Connect, Debezium connector configuration, schema registry). Polling is simpler for a portfolio project and demonstrates the same pattern
- **Spring's `@TransactionalEventListener`**: works for in-process events but loses events on app crash between commit and publish
