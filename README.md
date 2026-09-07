# OrderFlow — Event-Driven Order Management System

A production-grade order management backend built with Java 21 and Spring Boot 3.3, demonstrating event-driven architecture, transactional consistency patterns, and operational resilience.

## Architecture Overview

```
┌─────────────┐       ┌──────────────────────────────────────────────────┐
│   Client     │       │              Spring Boot Application             │
│  (REST API)  │──────▶│                                                  │
└─────────────┘       │  ┌────────────┐  ┌────────────┐  ┌───────────┐  │
                      │  │ Auth       │  │ Order      │  │ Product   │  │
                      │  │ Controller │  │ Controller │  │ Controller│  │
                      │  └─────┬──────┘  └─────┬──────┘  └─────┬─────┘  │
                      │        │               │               │        │
                      │  ┌─────▼──────┐  ┌─────▼──────┐  ┌────▼──────┐ │
                      │  │ Auth       │  │ Order      │  │ Product   │ │
                      │  │ Service    │  │ Service    │  │ Service   │ │
                      │  └────────────┘  └──────┬─────┘  └───────────┘ │
                      │                         │                       │
                      │              ┌──────────▼──────────┐            │
                      │              │   Outbox Service     │            │
                      │              │  (same transaction)  │            │
                      │              └──────────────────────┘            │
                      └──────────────────────┬─────────────────────────┘
                                             │
                      ┌──────────────────────▼─────────────────────────┐
                      │                 PostgreSQL 16                    │
                      │  orders │ products │ inventory │ outbox_events  │
                      └──────────────────────┬─────────────────────────┘
                                             │ poll
                      ┌──────────────────────▼─────────────────────────┐
                      │              Outbox Publisher                    │
                      │         (scheduled, at-least-once)              │
                      └──────────────────────┬─────────────────────────┘
                                             │ publish
                      ┌──────────────────────▼─────────────────────────┐
                      │             Kafka (Redpanda)                    │
                      │        orderflow.order.events                   │
                      └───┬──────────────┬──────────────┬──────────────┘
                          │              │              │
                   ┌──────▼─────┐ ┌──────▼─────┐ ┌─────▼───────┐
                   │  Payment   │ │ Inventory  │ │ Notification│
                   │  Consumer  │ │ Consumer   │ │ Consumer    │
                   └────────────┘ └────────────┘ └─────────────┘
```

## Key Features

**Event-Driven Architecture**
- Transactional outbox pattern: order + event written atomically in one DB transaction, published to Kafka asynchronously
- Three independent Kafka consumers with per-consumer-group idempotency via `processed_events` table
- Dead Letter Topic (DLT) with retry backoff for failed messages
- Fat events carry enough data for consumers to act without querying back

**Concurrency & Consistency**
- Pessimistic locking (`SELECT FOR UPDATE`) for inventory reservation under concurrent orders
- Two-field inventory model (quantity/reserved) supporting reservation, confirmation, and rollback
- Idempotent order creation via client-supplied idempotency keys
- Order state machine with enforced valid transitions

**Security**
- Stateless JWT authentication (no server-side sessions)
- Role-based access control (CUSTOMER, ADMIN)
- Redis-backed sliding window rate limiting with fail-open resilience

**Observability**
- Custom Micrometer metrics: order creation timers (p50/p95/p99), outbox lag gauge, consumer throughput counters
- Custom Actuator health indicator monitoring outbox lag
- Correlation ID propagation via MDC for distributed request tracing
- Prometheus-compatible metrics endpoint

**Caching**
- Redis cache-aside pattern for product catalog with 30-minute TTL
- Cache eviction on product/inventory updates

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.3.5 |
| Security | Spring Security + JWT (JJWT 0.12.6) |
| Database | PostgreSQL 16 |
| ORM | Spring Data JPA / Hibernate |
| Messaging | Apache Kafka (Redpanda) |
| Caching | Redis 7 |
| Metrics | Micrometer + Prometheus |
| Build | Maven |
| Containers | Docker + Docker Compose |
| Testing | JUnit 5, Mockito, Testcontainers |

## Project Structure

```
src/main/java/com/orderflow/
├── auth/                    # Authentication & authorization
│   ├── controller/          #   POST /api/auth/register, /login
│   ├── dto/                 #   Request/response records
│   ├── entity/              #   User, Role entities
│   ├── repository/          #   UserRepository, RoleRepository
│   ├── security/            #   JwtTokenProvider, JwtAuthenticationFilter, UserPrincipal
│   └── service/             #   AuthService
├── common/                  # Shared infrastructure
│   ├── dto/                 #   ApiResponse, PagedResponse
│   ├── entity/              #   BaseEntity (UUID, @Version, auditing)
│   └── exception/           #   GlobalExceptionHandler, custom exceptions
├── config/                  # Application configuration
│   ├── health/              #   OutboxHealthIndicator
│   ├── CorrelationIdFilter  #   Request correlation ID via MDC
│   ├── KafkaConfig          #   Topic creation
│   ├── KafkaConsumerConfig  #   Consumer factory, DLT, error handling
│   ├── MetricsConfig        #   Custom Micrometer metrics
│   ├── RateLimitingFilter   #   Redis-backed rate limiter
│   ├── RedisConfig          #   Cache manager, serialization
│   └── SecurityConfig       #   Filter chain, endpoint security rules
├── event/                   # Event-driven infrastructure
│   ├── consumer/            #   IdempotentConsumer base, Payment/Inventory/Notification
│   ├── dto/                 #   OrderEvent (fat event record)
│   ├── entity/              #   OutboxEvent, ProcessedEvent
│   ├── publisher/           #   OutboxPublisher (scheduled poller)
│   ├── repository/          #   OutboxEventRepository, ProcessedEventRepository
│   └── service/             #   OutboxService (MANDATORY propagation)
├── order/                   # Order domain
│   ├── controller/          #   POST/GET /api/orders, cancel
│   ├── dto/                 #   CreateOrderRequest, OrderResponse, etc.
│   ├── entity/              #   Order, OrderItem, OrderStatus (state machine)
│   ├── repository/          #   OrderRepository (JOIN FETCH, idempotency)
│   └── service/             #   OrderService (timed, metered)
└── product/                 # Product & inventory domain
    ├── controller/          #   CRUD /api/products, inventory updates
    ├── dto/                 #   CreateProductRequest, ProductResponse, etc.
    ├── entity/              #   Product, Inventory
    ├── repository/          #   ProductRepository, InventoryRepository (pessimistic lock)
    └── service/             #   ProductService (@Cacheable), InventoryService
```

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker & Docker Compose

### Run with Docker Compose

```bash
# Start all services (app + PostgreSQL + Redis + Redpanda + Console)
docker compose up -d

# View logs
docker compose logs -f app
```

The application starts at `http://localhost:8080`.
Redpanda Console (Kafka UI) is at `http://localhost:8888`.

### Run Locally (development)

```bash
# Start infrastructure only
docker compose up -d postgres redis redpanda redpanda-console

# Run the application
mvn spring-boot:run
```

### Run Tests

```bash
# Unit tests
mvn test

# Integration tests (requires Docker for Testcontainers)
mvn verify

# Specific integration test class
mvn test -Dtest="com.orderflow.integration.OrderIntegrationTest"
```

## API Reference

### Authentication

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/api/auth/register` | Register new user | Public |
| POST | `/api/auth/login` | Login, get JWT | Public |

### Products

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/products` | List products (paginated) | Public |
| GET | `/api/products/{id}` | Get product details | Public |
| POST | `/api/products` | Create product | ADMIN |
| PUT | `/api/products/{id}` | Update product | ADMIN |
| PUT | `/api/products/{id}/inventory` | Update stock | ADMIN |

### Orders

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/api/orders` | Create order (idempotent) | Authenticated |
| GET | `/api/orders` | List my orders (paginated) | Authenticated |
| GET | `/api/orders/{id}` | Get order details | Authenticated |
| POST | `/api/orders/{id}/cancel` | Cancel order | Authenticated |

### Observability

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/actuator/health` | Health check (includes outbox lag) |
| GET | `/actuator/prometheus` | Prometheus metrics |
| GET | `/actuator/info` | Application info |

## Order Lifecycle

```
     ┌──────────┐
     │  PENDING  │ ◄── Order created, inventory reserved
     └────┬─────┘
          │ Payment Consumer
     ┌────▼─────┐         ┌──────────┐
     │ CONFIRMED│         │  FAILED  │ ◄── Payment rejected (amount > $10,000)
     └────┬─────┘         └──────────┘
          │
     ┌────▼──────┐
     │ PROCESSING│
     └────┬──────┘
          │
     ┌────▼─────┐
     │  SHIPPED │
     └────┬─────┘
          │
     ┌────▼──────┐
     │ DELIVERED │
     └──────────┘

  PENDING or CONFIRMED ──▶ CANCELLED (user-initiated, releases inventory)
```

## Design Decisions

Detailed Architecture Decision Records are in the [`docs/adr/`](docs/adr/) directory:

- [ADR-001: Transactional Outbox Pattern](docs/adr/001-transactional-outbox.md) — Why outbox over direct Kafka publish
- [ADR-002: Pessimistic Locking for Inventory](docs/adr/002-pessimistic-locking.md) — Why pessimistic over optimistic for reservations
- [ADR-003: Stateless JWT Authentication](docs/adr/003-jwt-authentication.md) — Why JWT over server-side sessions
- [ADR-004: Redis Rate Limiting](docs/adr/004-redis-rate-limiting.md) — Why Redis over in-memory rate limiting

## Event Flow Example

**Order Creation:**
1. Client sends `POST /api/orders` with idempotency key
2. `OrderService` (single transaction):
   - Checks idempotency key → returns existing order if duplicate
   - Validates products exist and are active
   - Reserves inventory via pessimistic lock
   - Creates order + order items
   - Writes `ORDER_CREATED` event to outbox table
3. `OutboxPublisher` (1s polling interval):
   - Reads unpublished events from outbox
   - Publishes to Kafka (keyed by orderId for partition ordering)
   - Marks events as published
4. Kafka consumers (independent, idempotent):
   - **PaymentConsumer**: simulates payment → transitions to CONFIRMED or FAILED
   - **InventoryConsumer**: on CONFIRMED → confirms shipment; on FAILED → releases reservation
   - **NotificationConsumer**: logs notification for every state change

## Configuration

Key environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | orderflow | Database name |
| `DB_USERNAME` | orderflow | Database user |
| `DB_PASSWORD` | orderflow | Database password |
| `REDIS_HOST` | localhost | Redis host |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Kafka bootstrap servers |
| `JWT_SECRET` | (dev default) | JWT signing secret (change in production!) |

## Testing Strategy

**Unit Tests** — Mockito-based tests for services, security, and domain logic:
- `OrderServiceTest`: creation, cancellation, idempotency, state transitions
- `InventoryServiceTest`: reservation, release, insufficient stock
- `JwtTokenProviderTest`: token generation, validation, expiration
- `OutboxPublisherTest`: batch publishing, failure handling
- `PaymentConsumerTest` / `InventoryConsumerTest`: event handling, idempotency
- `RateLimitingFilterTest`: under/over limit, fail-open, header verification
- `OrderStatusTest`: parameterized state machine transitions

**Integration Tests** — Testcontainers with real PostgreSQL, Redis, Kafka:
- `AuthIntegrationTest`: register, login, duplicate email, invalid input
- `OrderIntegrationTest`: create, idempotency, cancel, inventory verification, authorization
- `ProductIntegrationTest`: CRUD, role-based access, public listing

## License

This is a portfolio project. Not licensed for production use.
