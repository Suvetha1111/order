# OrderFlow — Complete Interview Preparation Knowledge Base

> **Compiled from Claude session — September 2026**
> **Project:** OrderFlow — Event-Driven Order Management System
> **Author:** Suvetha S. (SDE-1/SDE-2 Portfolio Project)

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Java & Spring Boot Fundamentals](#2-java--spring-boot-fundamentals)
3. [JWT Authentication — How Stateless Auth Works](#3-jwt-authentication--how-stateless-auth-works)
4. [Spring Security Filter Chain](#4-spring-security-filter-chain)
5. [JPA, Hibernate & PostgreSQL](#5-jpa-hibernate--postgresql)
6. [Pessimistic vs. Optimistic Locking](#6-pessimistic-vs-optimistic-locking)
7. [Order State Machine](#7-order-state-machine)
8. [Transactional Outbox Pattern](#8-transactional-outbox-pattern)
9. [Apache Kafka — Event Streaming](#9-apache-kafka--event-streaming)
10. [Idempotent Consumers — Exactly-Once Semantics](#10-idempotent-consumers--exactly-once-semantics)
11. [Redis — Caching & Rate Limiting](#11-redis--caching--rate-limiting)
12. [Observability — Metrics, Health & Correlation IDs](#12-observability--metrics-health--correlation-ids)
13. [Docker Compose & Testcontainers](#13-docker-compose--testcontainers)
14. [Design Patterns Used](#14-design-patterns-used)
15. [Quick Reference: How It All Connects](#15-quick-reference-how-it-all-connects)

---

## 1. Project Overview

**OrderFlow** is a production-grade, event-driven order management system built with Java 21 and Spring Boot 3.3.5. It's a portfolio project demonstrating SDE-1/SDE-2 level backend engineering and systems design.

### Key Stats

- ~6,143 lines of Java
- 106 tests passing (83 unit + 23 integration)
- 79 source files (62 main + 17 test)
- 1 SQL migration (comprehensive schema)
- **Build tool:** Maven

### Architecture

- **Language:** Java 21
- **Framework:** Spring Boot 3.3.5
- **Database:** PostgreSQL 16 (via Spring Data JPA / Hibernate)
- **Cache:** Redis 7 (Spring Data Redis, cache-aside pattern)
- **Messaging:** Apache Kafka via Redpanda (Spring Kafka)
- **Auth:** JWT (JJWT 0.12.6, stateless, token in header only)
- **Observability:** Prometheus metrics (Micrometer), custom health indicators, correlation IDs
- **Testing:** JUnit 5, Testcontainers 2.0.5 (singleton container pattern)
- **Infrastructure:** Docker Compose (everything runs with `docker compose up`)

### Core Constraint: LOCAL-FIRST

Must be runnable locally without paid cloud infrastructure. `docker compose up` starts everything — PostgreSQL, Redis, Redpanda (Kafka-compatible), and the application.

### What This Project Deliberately Excludes

No microservices, no Kubernetes, no AWS, no complex frontend, no real payment gateway, no real email provider, no GraphQL, no WebSockets. These are intentional constraints to keep the scope focused on backend patterns that matter for SDE interviews.

---

## 2. Java & Spring Boot Fundamentals

### What Is Spring Boot?

Spring Boot is a framework that gives you a production-ready Java application with minimal configuration. Without Spring Boot, wiring together a web server, database connection, security, and caching would require hundreds of lines of boilerplate XML and configuration classes.

```
Without Spring Boot:
  - Write a main() method
  - Configure Tomcat manually
  - Write JDBC connection code
  - Wire together every component by hand
  - 500+ lines of configuration before any business logic

With Spring Boot:
  - Add spring-boot-starter-web to pom.xml
  - Write @SpringBootApplication on your main class
  - Spring Boot auto-configures everything
  - You write business logic
```

### Dependency Injection (The Core Idea)

"Dependency injection" means: **your class declares what it needs, and the framework gives it to you.** You never write `new OrderService(new OrderRepository(...))` — Spring creates all the objects and wires them together automatically.

```java
@Service
public class OrderService {
    // OrderService declares: "I need these five things"
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final OutboxService outboxService;

    // Spring Boot sees this constructor and says:
    // "I have beans of all these types — here you go"
    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        InventoryService inventoryService,
                        OutboxService outboxService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
        this.outboxService = outboxService;
    }
}
```

**Why it matters:**
- Classes don't create their own dependencies → you can swap in mocks for testing
- The framework manages object lifecycles → no manual cleanup
- Configuration is centralized → change a database URL in one place, not thirty

### Maven — Build Tool

Maven is the build tool. The `pom.xml` file declares everything the project needs:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>

<properties>
    <java.version>21</java.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- Each "starter" pulls in a tree of related libraries -->
</dependencies>
```

`mvn test` compiles everything, runs all 106 tests, and reports results. `mvn package` produces a fat JAR — one file containing your code + all dependencies + an embedded Tomcat server.

### Java 21 Features Used in OrderFlow

**Records** — immutable data carriers with no boilerplate:

```java
// This single line gives you: constructor, getters, equals, hashCode, toString
public record OrderEvent(
    UUID eventId,
    String eventType,
    UUID orderId,
    UUID userId,
    Instant timestamp,
    int version,
    BigDecimal totalAmount,
    String orderStatus,
    List<OrderEventItem> items
) {}
```

**Switch expressions with pattern matching:**

```java
public boolean canTransitionTo(OrderStatus target) {
    return switch (this) {
        case PENDING    -> Set.of(CONFIRMED, CANCELLED, FAILED).contains(target);
        case CONFIRMED  -> Set.of(PROCESSING, CANCELLED, FAILED).contains(target);
        case PROCESSING -> Set.of(SHIPPED, FAILED).contains(target);
        case SHIPPED    -> Set.of(DELIVERED, FAILED).contains(target);
        case DELIVERED, CANCELLED, FAILED -> false;  // terminal states
    };
}
```

**Text blocks** (multi-line strings with `"""`):

```java
response.getWriter().write("""
    {"success":false,"error":"Rate limit exceeded. Try again in %d seconds."}
    """.formatted(ttl));
```

---

## 3. JWT Authentication — How Stateless Auth Works

### The Problem with Server-Side Sessions

Traditional auth stores login state on the server:

```
User logs in → Server creates session object → stores it in memory/DB
Every request → User sends session cookie → Server looks up session
```

**Problems:**
- Server must store every active session in memory or a database
- Scaling to multiple servers requires shared session storage (sticky sessions or session replication)
- Each request requires a DB lookup to verify the session

### What Is a JWT?

JWT (JSON Web Token) is a self-contained token — it carries the user's identity and permissions **inside the token itself**, signed so the server can trust it without any database lookup.

A JWT has three parts, separated by dots:

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLWlkIiwiZW1haWwiOiJ0ZXN0QGV4YW1wbGUuY29tIiwicm9sZXMiOlsiQ1VTVE9NRVIiXX0.signature
```

```
Part 1: Header    → {"alg":"HS256"}            (which algorithm signed it)
Part 2: Payload   → {"sub":"user-id",          (claims — the actual data)
                      "email":"test@example.com",
                      "roles":["CUSTOMER"],
                      "exp":1725580800}
Part 3: Signature → HMAC-SHA256(header + "." + payload, secret_key)
```

The signature is key: the server signs the payload with a secret key. If anyone tampers with the payload (e.g., changes "CUSTOMER" to "ADMIN"), the signature won't match, and the server rejects it.

### How OrderFlow Creates Tokens

```java
// JwtTokenProvider.java
public String generateToken(UUID userId, String email, List<String> roles) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + expirationMs);  // 1 hour

    return Jwts.builder()
            .subject(userId.toString())     // "sub" claim = user ID
            .claim("email", email)          // custom claim
            .claim("roles", roles)          // custom claim: ["CUSTOMER"] or ["ADMIN"]
            .issuedAt(now)                  // "iat" claim
            .expiration(expiry)             // "exp" claim — auto-rejected after this
            .signWith(key)                  // HMAC-SHA256 with secret key
            .compact();                     // produce the encoded string
}
```

### How OrderFlow Validates Tokens (Every Request)

```java
// JwtAuthenticationFilter.java — runs on every request
String token = extractToken(request);  // get "Bearer xxx" from Authorization header

if (token != null && tokenProvider.validateToken(token)) {
    UUID userId = tokenProvider.getUserIdFromToken(token);
    String email = tokenProvider.getEmailFromToken(token);
    List<String> roles = tokenProvider.getRolesFromToken(token);

    // Create authorities with ROLE_ prefix (Spring Security convention)
    List<SimpleGrantedAuthority> authorities = roles.stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList();

    // Set the SecurityContext — now this request is "authenticated"
    UserPrincipal principal = new UserPrincipal(userId, email, authorities);
    UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(principal, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
}
```

**Critical design decision:** The JWT token is returned ONLY in the `Authorization` response header, NOT in the response body. This follows security best practices — the token never appears in JSON responses that could be logged, cached, or exposed in browser DevTools network responses.

```java
// AuthController.java — login endpoint
@PostMapping("/login")
public ResponseEntity<ApiResponse<AuthResponse>> login(@RequestBody LoginRequest request) {
    LoginResult result = authService.login(request);
    return ResponseEntity.ok()
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + result.token())  // token in header
            .body(ApiResponse.success(result.authResponse()));  // body has NO token
}
```

### Why Stateless is Better for This Architecture

```
Stateful (sessions):
  Request → Cookie → DB lookup (is session valid?) → Process

Stateless (JWT):
  Request → Token → Verify signature (CPU only, no DB) → Process
```

No session table, no session replication between servers, no Redis session store. Each server independently verifies the token signature — the secret key is the only shared state.

---

## 4. Spring Security Filter Chain

### What Is a Filter Chain?

Every HTTP request passes through a series of "filters" before reaching your controller. Think of it like airport security — your request goes through multiple checkpoints in order:

```
HTTP Request
  ↓
  CorrelationIdFilter    [Order: HIGHEST_PRECEDENCE]
  — assigns a tracing ID
  ↓
  JwtAuthenticationFilter [Before UsernamePasswordAuth]
  — extracts JWT, sets SecurityContext
  ↓
  RateLimitingFilter      [After JwtAuth]
  — checks Redis counter
  ↓
  Spring Security AuthorizationFilter
  — checks if user has permission for this endpoint
  ↓
  Your Controller Method
```

### SecurityConfig — The Rulebook

```java
http
    .csrf(csrf -> csrf.disable())           // No CSRF — API uses JWT, not cookies
    .sessionManagement(session ->
        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/**").permitAll()        // Anyone can register/login
        .requestMatchers("/actuator/health/**").permitAll() // Health checks are public
        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()  // Browse products
        .requestMatchers(HttpMethod.POST, "/api/products/**").hasRole("ADMIN") // Create products
        .anyRequest().authenticated()                      // Everything else: need JWT
    )

    .exceptionHandling(ex -> ex
        .authenticationEntryPoint((request, response, authException) ->
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))

    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
    .addFilterAfter(rateLimitingFilter, JwtAuthenticationFilter.class);
```

**Why CSRF is disabled:** CSRF protection is for cookie-based auth (browser sends cookies automatically, so a malicious site can trigger actions). JWT-based APIs don't use cookies — the client must explicitly attach the `Authorization` header, so CSRF attacks don't apply.

**Why STATELESS sessions:** Tells Spring "never create an HttpSession." Without this, Spring Security creates a session by default, defeating the purpose of JWT.

### OncePerRequestFilter

Every custom filter extends `OncePerRequestFilter` — a Spring class that guarantees your filter runs exactly once per request, even if the request is forwarded internally.

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Your logic here
        filterChain.doFilter(request, response);  // pass to next filter
    }
}
```

`filterChain.doFilter(request, response)` is the key — it says "I'm done, pass the request to the next filter in the chain." If you don't call it, the request stops here (which is what the rate limiter does when you're over the limit).

---

## 5. JPA, Hibernate & PostgreSQL

### What Is JPA?

JPA (Java Persistence API) is a specification — a set of interfaces that define how Java objects map to database tables. **Hibernate** is the implementation that does the actual work.

```
Your Java class → [JPA annotations say how to map it] → [Hibernate translates to SQL] → PostgreSQL
```

### Entity Mapping

```java
@Entity                              // This class maps to a database table
@Table(name = "orders")              // The table is called "orders"
public class Order extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)   // Many orders → one user
    @JoinColumn(name = "user_id")        // Foreign key column
    private User user;

    @Enumerated(EnumType.STRING)         // Store enum as "PENDING", not 0
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.PENDING;

    @OneToMany(mappedBy = "order",       // One order → many items
               cascade = CascadeType.ALL,
               orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();
}
```

**FetchType.LAZY** — don't load the user from the database until you actually call `order.getUser()`. Without this, loading one order would also load the user, all their roles, etc. — a cascade of unnecessary queries called the "N+1 problem."

**CascadeType.ALL** — when you save/delete an Order, automatically save/delete its OrderItems too. You don't need separate `orderItemRepository.save()` calls.

**orphanRemoval = true** — if you remove an item from the `items` list, Hibernate automatically deletes it from the database.

### BaseEntity — Shared Fields

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;               // Every entity gets a UUID primary key

    @Version
    private Long version;          // Optimistic locking (explained in Section 6)

    @CreatedDate
    private Instant createdAt;     // Auto-set when entity is first saved

    @LastModifiedDate
    private Instant updatedAt;     // Auto-updated on every save
}
```

`@MappedSuperclass` means "these columns exist in every child table, but there's no `base_entity` table." It's Java inheritance mapped to shared columns.

### The SQL Schema

```sql
CREATE TABLE IF NOT EXISTS orders (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id),
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    total_amount    NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    idempotency_key VARCHAR(255) UNIQUE,    -- for idempotent order creation
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
```

**Why `NUMERIC(12,2)` for money?** Never use `float` or `double` for money — they have floating-point rounding errors (`0.1 + 0.2 = 0.30000000000000004`). `NUMERIC` is exact decimal arithmetic. `BigDecimal` in Java maps to this.

**Why UUID primary keys?** Auto-increment IDs leak information (competitor can see you have 10,000 orders) and conflict during database merges. UUIDs are globally unique and reveal nothing.

**Why `TIMESTAMPTZ`?** "Timestamp with time zone" — stores the instant in UTC. Without the `TZ`, PostgreSQL stores a "local time" with no timezone context, which breaks when servers are in different timezones.

---

## 6. Pessimistic vs. Optimistic Locking

### The Lost-Update Problem

Two users try to buy the last 5 units of a product at the same time:

```
Time 1: User A reads inventory → available = 5
Time 2: User B reads inventory → available = 5
Time 3: User A reserves 3     → writes available = 2 ✓
Time 4: User B reserves 4     → writes available = 1 ✓  ← WRONG!
```

Both read `available = 5` and both succeed, but we only had 5 units total. We sold 7. This is the "lost update" problem.

### Optimistic Locking (BaseEntity @Version)

**Assumption:** Conflicts are rare. Let everyone read freely, but check for conflicts at write time.

```java
@Version
private Long version;  // starts at 0, incremented on every save
```

How it works:

```sql
-- Hibernate generates:
UPDATE orders SET status = 'CONFIRMED', version = 2
WHERE id = 'abc-123' AND version = 1;
-- If someone else already changed it (version is now 2), 
-- this UPDATE affects 0 rows → Hibernate throws OptimisticLockException
```

**Used in OrderFlow for:** All entities via `BaseEntity`. Prevents concurrent modifications to the same order, product, or user.

### Pessimistic Locking (Inventory Reservation)

**Assumption:** Conflicts are likely. Lock the row in the database so nobody else can read or write it until you're done.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT i FROM Inventory i WHERE i.product.id = :productId")
Optional<Inventory> findByProductIdForUpdate(@Param("productId") UUID productId);
```

This generates:

```sql
SELECT * FROM inventory WHERE product_id = 'prod-123' FOR UPDATE;
-- The row is now LOCKED. Other transactions trying to read it
-- with FOR UPDATE will BLOCK until this transaction commits.
```

**Why pessimistic for inventory?** During order placement, multiple concurrent requests for the same product would all read the same available quantity. With optimistic locking, all but one would fail and need retry logic. Pessimistic locking serializes the critical section — each request gets a definitive answer on stock availability.

```java
// Inventory.java — the two-field model
public int getAvailable() {
    return quantity - reserved;  // available = total stock - reserved for pending orders
}

public void reserve(int amount) {
    if (amount > getAvailable()) {
        throw new InsufficientInventoryException(product.getName(), amount, getAvailable());
    }
    this.reserved += amount;  // reserved goes up, quantity stays the same
}

public void confirmShipment(int amount) {
    this.quantity -= amount;   // actually deduct from total stock
    this.reserved -= amount;   // no longer reserved — it shipped
}

public void releaseReservation(int amount) {
    this.reserved = Math.max(0, this.reserved - amount);  // order cancelled — unreserve
}
```

### The Two-Field Inventory Lifecycle

```
Initial state:     quantity=100, reserved=0,  available=100

Step 1 — Reserve:  quantity=100, reserved=3,  available=97
  (Order created, 3 units reserved for this order)

Step 2a — Confirm: quantity=97,  reserved=0,  available=97
  (Payment cleared, shipped → deduct from both)

Step 2b — Release: quantity=100, reserved=0,  available=100
  (Order cancelled → give back the reservation)
```

---

## 7. Order State Machine

### What Is a State Machine?

A state machine defines all possible states an entity can be in and all valid transitions between them. It prevents invalid states — you can't ship a cancelled order, and you can't cancel an already-delivered order.

```
                         ┌──────────┐
                         │ PENDING  │
                         └────┬─────┘
                     ┌────────┼──────────┐
                     ▼        ▼          ▼
               ┌──────────┐ ┌──────────┐ ┌──────────┐
               │CONFIRMED │ │CANCELLED │ │  FAILED  │
               └────┬─────┘ └──────────┘ └──────────┘
                    ▼
               ┌──────────┐
               │PROCESSING│──────────────►┌──────────┐
               └────┬─────┘               │  FAILED  │
                    ▼                     └──────────┘
               ┌──────────┐
               │ SHIPPED  │──────────────►┌──────────┐
               └────┬─────┘               │  FAILED  │
                    ▼                     └──────────┘
               ┌──────────┐
               │DELIVERED │
               └──────────┘

Terminal states: DELIVERED, CANCELLED, FAILED (no transitions out)
```

### Implementation with Java Enum

```java
public enum OrderStatus {
    PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED, FAILED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING    -> Set.of(CONFIRMED, CANCELLED, FAILED).contains(target);
            case CONFIRMED  -> Set.of(PROCESSING, CANCELLED, FAILED).contains(target);
            case PROCESSING -> Set.of(SHIPPED, FAILED).contains(target);
            case SHIPPED    -> Set.of(DELIVERED, FAILED).contains(target);
            case DELIVERED, CANCELLED, FAILED -> false;  // terminal states
        };
    }

    public boolean isCancellable() {
        return this == PENDING || this == CONFIRMED;
    }
}
```

The `Order` entity enforces this:

```java
public void transitionTo(OrderStatus newStatus) {
    if (!this.status.canTransitionTo(newStatus)) {
        throw new IllegalStateException(
                "Cannot transition order from %s to %s".formatted(this.status, newStatus));
    }
    this.status = newStatus;
}
```

**Why an enum and not a state machine library?** For 7 states with simple rules, an enum switch is clear, testable, and requires no external dependency. A library (Spring Statemachine) adds complexity that would only pay off with dozens of states and complex side-effects.

---

## 8. Transactional Outbox Pattern

### The Dual-Write Problem

When an order is created, two things must happen: save the order to PostgreSQL and publish an event to Kafka. But what if one succeeds and the other fails?

```
Scenario A — Publish first, then save:
  1. Publish ORDER_CREATED to Kafka → ✓
  2. Save order to PostgreSQL       → CRASH!
  Result: Consumers process an event for an order that doesn't exist

Scenario B — Save first, then publish:
  1. Save order to PostgreSQL       → ✓
  2. Publish ORDER_CREATED to Kafka → CRASH!
  Result: Order exists but nobody knows about it — no payment, no notification
```

Neither order works. The fundamental problem: PostgreSQL and Kafka are two different systems. You can't put them in the same atomic transaction.

### The Outbox Solution

Instead of publishing directly to Kafka, write the event to a table in the **same database** in the **same transaction** as the business operation.

```
BEGIN TRANSACTION
  1. INSERT INTO orders (...)                  -- save the order
  2. INSERT INTO outbox_events (...)           -- save the event
COMMIT
```

Both writes are in the same PostgreSQL transaction. Either both succeed or both fail — that's what transactions guarantee. A separate process (the "outbox publisher") polls this table and publishes events to Kafka.

### OrderFlow's Implementation

**Step 1 — OutboxService writes the event (same transaction):**

```java
@Transactional(propagation = Propagation.MANDATORY)  // MUST be called within existing TX
public void saveOrderEvent(OrderEvent event) {
    String payload = objectMapper.writeValueAsString(event);

    OutboxEvent outboxEvent = new OutboxEvent(
            "Order",                    // aggregate type
            event.orderId(),            // aggregate ID (for partition key)
            event.eventType(),          // e.g., "ORDER_CREATED"
            payload                     // full event JSON
    );

    outboxRepository.save(outboxEvent);
}
```

`Propagation.MANDATORY` means "there MUST already be a transaction running when you call me." If someone accidentally calls `saveOrderEvent()` outside a transaction, Spring throws an exception immediately. This is a safety net — the whole point is that the event is in the same transaction as the order.

**Step 2 — OutboxPublisher polls and publishes:**

```java
@Scheduled(fixedDelayString = "${orderflow.outbox.poll-interval-ms}")  // every 1 second
@Transactional
public void publishPendingEvents() {
    List<OutboxEvent> events = outboxRepository.findUnpublishedBatch(batchSize);

    for (OutboxEvent event : events) {
        try {
            kafkaTemplate.send(
                    orderEventsTopic,
                    event.getAggregateId().toString(),  // key = orderId → same partition
                    event.getPayload()
            ).get();  // Block until Kafka confirms receipt

            event.markPublished();
            outboxRepository.save(event);
        } catch (Exception e) {
            break;  // Stop on failure to preserve ordering
        }
    }
}
```

**Why `.get()` (blocking)?** Kafka's `send()` is async by default. We need to know if the send succeeded before marking the event as published. `.get()` blocks until Kafka acknowledges receipt.

**Why `break` on failure?** If event #3 fails, we stop. We don't skip to event #4 because events for the same order must be published in order (CREATED before CONFIRMED). The failed event will be retried on the next poll cycle.

### The Outbox Table

```sql
CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(100)  NOT NULL,
    aggregate_id    UUID          NOT NULL,    -- order ID, used as Kafka key
    event_type      VARCHAR(100)  NOT NULL,    -- ORDER_CREATED, ORDER_CONFIRMED, etc.
    payload         JSONB         NOT NULL,    -- full event data as JSON
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    published       BOOLEAN       NOT NULL DEFAULT FALSE,
    published_at    TIMESTAMPTZ
);

-- Partial index: only index unpublished events (the ones the poller cares about)
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox_events(published, created_at)
    WHERE published = FALSE;
```

**Why a partial index?** Over time, 99% of events are published. The poller only queries `WHERE published = FALSE`. A partial index only indexes the rows matching the `WHERE` clause, keeping the index small and queries fast even with millions of published events.

### Delivery Guarantee

The outbox pattern provides **at-least-once delivery**. If the publisher crashes after sending to Kafka but before marking as published, the event is sent again on the next poll. This means consumers must be **idempotent** (see Section 10).

**Why not exactly-once?** True exactly-once requires Kafka transactions that span both the Kafka producer and the database — possible but complex. At-least-once with idempotent consumers achieves the same outcome with simpler code.

---

## 9. Apache Kafka — Event Streaming

### What Is Kafka?

Kafka is a distributed event streaming platform. Think of it as a durable, ordered, replayable message queue.

```
Traditional message queue (RabbitMQ):
  Producer → Queue → Consumer reads and message is DELETED

Kafka:
  Producer → Topic → Consumer reads and message STAYS
  Another consumer can read the same message independently
  Messages are retained for a configurable period (default 7 days)
```

### Key Kafka Concepts

**Topic** — A named stream of events. Like a database table for events. OrderFlow has two topics:
- `orderflow.order.events` (3 partitions) — all order events
- `orderflow.order.events.dlt` (1 partition) — dead letter topic for failed events

**Partition** — A topic is split into partitions. Each partition is an ordered, immutable sequence of events. OrderFlow uses 3 partitions for the events topic.

**Message Key** — Determines which partition a message goes to. OrderFlow uses the orderId as the key, so all events for the same order go to the same partition → they're consumed in order.

```java
kafkaTemplate.send(
    orderEventsTopic,
    event.getAggregateId().toString(),  // key = orderId
    event.getPayload()                  // value = event JSON
);
```

**Consumer Group** — A named group of consumers that share the work. Each partition is assigned to exactly one consumer in the group. OrderFlow has three consumer groups, each processing events independently:
- `payment-consumer` — simulates payment
- `inventory-consumer` — confirms or releases inventory
- `notification-consumer` — sends notifications

```
Topic: orderflow.order.events (3 partitions)

Consumer Group: payment-consumer
  Consumer 1 → reads Partition 0 and 1
  Consumer 2 → reads Partition 2

Consumer Group: notification-consumer (separate group)
  Consumer 1 → reads ALL partitions independently
```

### Kafka Producer Configuration

```yaml
spring:
  kafka:
    producer:
      acks: all           # Wait for ALL replicas to confirm
      retries: 3           # Retry on transient failures
      properties:
        enable.idempotence: true           # Prevent duplicate messages
        max.in.flight.requests.per.connection: 5  # Pipeline up to 5 requests
```

**`acks: all`** — The producer waits for every replica of the partition to confirm the write. Slower but guarantees no data loss. `acks: 1` only waits for the leader, and you lose data if the leader crashes before replicating.

**`enable.idempotence: true`** — Each producer gets a unique ID, and each message gets a sequence number. If a retry accidentally sends the same message twice, Kafka deduplicates it.

### Error Handling — Dead Letter Topic

When a consumer can't process a message after 3 retries, it goes to the Dead Letter Topic (DLT):

```java
// Retry 3 times, 1 second apart
DefaultErrorHandler errorHandler = new DefaultErrorHandler(
    new DeadLetterPublishingRecoverer(kafkaTemplate),  // → send to .dlt topic
    new FixedBackOff(1000L, 3L)                        // 1s interval, 3 attempts
);
```

```
Message fails → retry 1 (1s) → retry 2 (1s) → retry 3 (1s) → Dead Letter Topic
```

The DLT preserves the original message for debugging and manual reprocessing via Redpanda Console (accessible at `localhost:8888`).

### Manual Acknowledgment

```java
factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
```

With auto-commit, Kafka commits offsets on a timer — even if processing failed. Manual ack means: "I'll tell you when I'm done."

```java
// In IdempotentConsumer.consume():
handleEvent(event);                    // process the business logic
processedEventRepository.save(...);     // record as processed
ack.acknowledge();                     // NOW tell Kafka we're done
```

The offset is committed only after the business logic and idempotency record are saved. If the consumer crashes before `ack.acknowledge()`, Kafka redelivers the message.

---

## 10. Idempotent Consumers — Exactly-Once Semantics

### The Problem

The outbox pattern guarantees at-least-once delivery. Combined with Kafka consumer rebalances and restarts, the same event can be delivered multiple times. Without protection, payment would be charged twice, inventory would be deducted twice, etc.

### The Solution: processed_events Table

```sql
CREATE TABLE IF NOT EXISTS processed_events (
    event_id        UUID         NOT NULL,
    consumer_group  VARCHAR(100) NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer_group)
);
```

The composite primary key `(event_id, consumer_group)` means: each consumer group can process each event exactly once. The same event is processed independently by payment, inventory, and notification consumers.

### Template Method Pattern

`IdempotentConsumer` is an abstract base class — it handles deserialization, idempotency checking, and acknowledgment. Concrete consumers only implement `handleEvent()`:

```java
public abstract class IdempotentConsumer {

    // Template method — the algorithm skeleton
    @Transactional
    public void consume(String payload, Acknowledgment ack) {
        // 1. Deserialize
        OrderEvent event = objectMapper.readValue(payload, OrderEvent.class);

        // 2. Idempotency check — already processed?
        if (processedEventRepository.existsByEventIdAndConsumerGroup(
                event.eventId(), consumerGroup())) {
            ack.acknowledge();  // skip, already done
            return;
        }

        // 3. Delegate to concrete consumer (template method)
        handleEvent(event);

        // 4. Record as processed (same transaction as business logic)
        processedEventRepository.save(new ProcessedEvent(event.eventId(), consumerGroup()));

        // 5. Acknowledge Kafka offset
        ack.acknowledge();
    }

    // Subclasses implement this
    protected abstract void handleEvent(OrderEvent event);
    protected abstract String consumerGroup();
}
```

### The Three Consumers

**PaymentConsumer** — Handles `ORDER_CREATED`:
```java
protected void handleEvent(OrderEvent event) {
    if (!OrderEvent.ORDER_CREATED.equals(event.eventType())) return;

    boolean paymentSuccess = event.totalAmount().compareTo(new BigDecimal("10000.00")) <= 0;

    if (paymentSuccess) {
        orderService.transitionOrder(event.orderId(), OrderStatus.CONFIRMED);
    } else {
        orderService.transitionOrder(event.orderId(), OrderStatus.FAILED);
    }
}
```

**InventoryConsumer** — Handles `ORDER_CONFIRMED` (confirm shipment) and `ORDER_FAILED`/`ORDER_CANCELLED` (release):
```java
protected void handleEvent(OrderEvent event) {
    switch (event.eventType()) {
        case OrderEvent.ORDER_CONFIRMED -> {
            for (OrderEvent.OrderEventItem item : event.items()) {
                inventoryService.confirmShipment(item.productId(), item.quantity());
            }
        }
        case OrderEvent.ORDER_FAILED, OrderEvent.ORDER_CANCELLED -> {
            for (OrderEvent.OrderEventItem item : event.items()) {
                inventoryService.releaseStock(item.productId(), item.quantity());
            }
        }
    }
}
```

**NotificationConsumer** — Handles ALL event types, logs the notification:
```java
protected void handleEvent(OrderEvent event) {
    String message = switch (event.eventType()) {
        case OrderEvent.ORDER_CREATED   -> "Your order #%s has been placed. Total: $%s";
        case OrderEvent.ORDER_CONFIRMED -> "Payment confirmed for order #%s.";
        case OrderEvent.ORDER_CANCELLED -> "Order #%s has been cancelled.";
        // ... all statuses covered
    };
    log.info("[notification] → userId={}: {}", event.userId(), message);
}
```

### Fat Events

OrderFlow uses "fat events" — each event carries enough data for consumers to act without querying back to the order service:

```java
public record OrderEvent(
    UUID eventId,           // unique ID for idempotency
    String eventType,       // "ORDER_CREATED", etc.
    UUID orderId,           // which order
    UUID userId,            // who placed it
    Instant timestamp,      // when
    int version,            // schema version for evolution
    BigDecimal totalAmount, // how much (for payment processing)
    String orderStatus,     // current status
    List<OrderEventItem> items  // product IDs, quantities, prices
) {}
```

**Why fat events?** If the event only contained `{orderId: "abc"}`, every consumer would need to call the order service to get the details — creating tight coupling and increasing load. Fat events are self-contained.

---

## 11. Redis — Caching & Rate Limiting

### What Is Redis?

Redis is an **in-memory key-value store** — think of it as a Java `Map<String, String>` that runs as a separate server. Because data lives in RAM, reads and writes take microseconds.

### Cache-Aside Pattern (Product Caching)

```
App: "Give me product #42"
  → Check Redis: "Do you have product #42?"
  → Redis: "No" (cache miss)
  → Ask PostgreSQL: "Give me product #42"
  → PostgreSQL returns the product
  → Store in Redis: "Remember product #42 for 30 minutes"
  → Return product to the caller

Next request (within 30 minutes):
  → Check Redis: "Do you have product #42?"
  → Redis: "Yes, here it is" (cache hit)
  → Return immediately — PostgreSQL is never touched
```

### Implementation with Spring Cache

```java
// ProductService.java
@Cacheable(value = "products", key = "#productId")  // cache the result
public ProductResponse getProduct(UUID productId) {
    // This method body runs ONLY on cache miss
    Product product = productRepository.findById(productId)
            .orElseThrow(() -> new EntityNotFoundException("Product not found"));
    return ProductResponse.from(product);
}

// When inventory changes, invalidate the cache
@CacheEvict(value = "products", key = "#productId")
public void updateInventory(UUID productId, int newQuantity) {
    // After this method runs, the cached product is deleted from Redis
    // Next read will hit the database and cache fresh data
}
```

**`@Cacheable`** — before executing the method, check Redis. If found, return the cached value and skip the method entirely. If not found, execute the method and store the result in Redis.

**`@CacheEvict`** — after executing the method, delete the cached entry. This ensures stale data isn't served after an update.

### Redis Configuration — Serialization

```java
@Configuration
@EnableCaching
public class RedisConfig {

    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());           // handles Instant, LocalDate
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL);         // include type info in JSON
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
```

**Why `JavaTimeModule`?** Without it, Java 8+ date types (`Instant`, `LocalDateTime`) fail to serialize. The module teaches Jackson how to read and write these types.

**Why `activateDefaultTyping`?** When Redis stores `ProductResponse`, it needs to know the class type to deserialize it back. This adds a `@class` field to the JSON so Jackson knows which class to instantiate.

### Redis-Backed Rate Limiting

```java
// RateLimitingFilter.java — sliding window with INCR + EXPIRE

String redisKey = "rate_limit:" + clientKey;  // e.g., "rate_limit:user:abc-123"

Long currentCount = redisTemplate.opsForValue().increment(redisKey);
// INCR creates the key with value 1 if it doesn't exist,
// or increments it if it does — ATOMIC operation

if (currentCount == 1L) {
    // First request in this window — set expiry
    redisTemplate.expire(redisKey, Duration.ofSeconds(60));
    // After 60 seconds, Redis auto-deletes the key → counter resets
}

if (currentCount > 100) {  // over the limit
    response.setStatus(429);  // Too Many Requests
    return;  // don't call filterChain.doFilter — request stops here
}
```

**Why Redis instead of in-memory?** In-memory counters (like a `ConcurrentHashMap`) work for a single server. But if you scale to 3 servers behind a load balancer, each server has its own counter — a user could make 100 requests to each server (300 total) and never hit the limit. Redis provides a single shared counter.

**Why "fail open"?** If Redis is unavailable:

```java
} catch (Exception e) {
    log.warn("Rate limiter Redis error, failing open: {}", e.getMessage());
    // Let the request through — better to serve without rate limiting
    // than to reject all requests because Redis is down
}
```

### Rate Limit Headers

```
X-RateLimit-Limit: 100        ← maximum requests per window
X-RateLimit-Remaining: 73     ← requests left before hitting the limit
X-RateLimit-Reset: 42         ← seconds until the window resets
```

These are standard headers used by GitHub, Stripe, and most production APIs. Clients use them to implement backoff without trial and error.

### Redis in Docker Compose

```yaml
redis:
  image: redis:7-alpine
  command: redis-server --maxmemory 128mb --maxmemory-policy allkeys-lru
```

**`--maxmemory 128mb`** — Redis is capped at 128MB of RAM.

**`--maxmemory-policy allkeys-lru`** — When Redis hits 128MB, it evicts the **Least Recently Used** key across all keys. This means the cache is self-managing — it never runs out of memory, and infrequently accessed entries are evicted first.

---

## 12. Observability — Metrics, Health & Correlation IDs

### Custom Prometheus Metrics (Micrometer)

Micrometer is the "SLF4J of metrics" — a vendor-neutral API for recording metrics. You code against Micrometer, and it exports to Prometheus, DataDog, CloudWatch, etc.

**Gauges** — Current value (goes up and down):
```java
Gauge.builder("outbox.pending.count", outboxRepository,
        repo -> repo.findUnpublishedBatch(Integer.MAX_VALUE).size())
    .description("Number of unpublished outbox events (outbox lag)")
    .register(registry);
```

A gauge is like a car's speedometer — it shows the current value right now. "There are currently 5 unpublished outbox events."

**Counters** — Running total (only goes up):
```java
Counter ordersCreated = Counter.builder("orders.created.total")
    .description("Total number of orders created")
    .register(meterRegistry);

// In business logic:
ordersCreated.increment();
```

A counter is like a car's odometer — it only goes up. "We have created 10,000 orders total since startup."

**Timers** — Duration of operations (with percentiles):
```java
Timer orderCreationTimer = Timer.builder("order.creation.time")
    .publishPercentiles(0.5, 0.95, 0.99)  // p50, p95, p99
    .register(registry);

// Usage — wraps the entire method execution:
return orderCreationTimer.record(() -> doCreateOrder(userId, request));
```

A timer is like a stopwatch — it records how long each operation takes. "50% of orders are created in under 20ms (p50), 95% under 100ms (p95), 99% under 500ms (p99)."

**Why p50/p95/p99?** Averages hide problems. If 99 requests take 10ms and 1 takes 10 seconds, the average is 109ms — looks fine. But p99 = 10,000ms screams "something is wrong for 1% of users."

### Custom Health Indicator

```java
@Component
public class OutboxHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        int pendingCount = outboxRepository.findUnpublishedBatch(101).size();

        if (pendingCount > 100) {
            return Health.down()
                .withDetail("pending_events", pendingCount)
                .withDetail("message", "Outbox lag exceeds threshold — Kafka may be unavailable")
                .build();
        }

        if (pendingCount > 10) {
            return Health.up()
                .withDetail("status", "DEGRADED")
                .withDetail("message", "Outbox lag elevated — monitor closely")
                .build();
        }

        return Health.up().withDetail("pending_events", pendingCount).build();
    }
}
```

Exposed at `GET /actuator/health`. Kubernetes or a load balancer checks this endpoint — if it returns DOWN, the instance is pulled from rotation.

**Three states:**
- `< 10 pending` → UP (healthy)
- `10–100 pending` → UP with DEGRADED warning (early alert)
- `> 100 pending` → DOWN (outbox publisher can't keep up — Kafka might be unreachable)

### Correlation ID — Distributed Tracing

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)  // runs FIRST in the filter chain
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put("correlationId", correlationId);       // put in thread-local context
        response.setHeader("X-Correlation-Id", correlationId); // echo back

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");               // clean up
        }
    }
}
```

**MDC (Mapped Diagnostic Context)** is a thread-local map. Once you put `correlationId` in MDC, every log statement from that request includes it:

```
logging.pattern.console: "%d{ISO8601} [%thread] [%X{correlationId:-}] %-5level %logger{36} - %msg%n"
```

```
2026-09-04 10:30:00 [http-nio-8080-exec-1] [abc-123-def] INFO  OrderService - Order created: orderId=...
2026-09-04 10:30:01 [http-nio-8080-exec-1] [abc-123-def] DEBUG OutboxService - Outbox event saved: ...
```

Now you can search `abc-123-def` in your logs and see every log line from that single request, across all classes and services.

---

## 13. Docker Compose & Testcontainers

### Docker Compose — Local Development

```yaml
services:
  app:        # Spring Boot application
  postgres:   # PostgreSQL 16 (primary database)
  redis:      # Redis 7 (caching + rate limiting)
  redpanda:   # Kafka-compatible event streaming
  redpanda-console:  # Web UI for debugging Kafka topics
```

**Why Redpanda instead of Apache Kafka?** Redpanda is Kafka-API-compatible but written in C++ (not JVM-based). It starts faster, uses less memory, and requires no ZooKeeper — perfect for local development. From the application's perspective, it IS Kafka. The Spring Kafka client doesn't know the difference.

**Health checks** ensure services start in the right order:

```yaml
postgres:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U orderflow"]  # checks PostgreSQL is accepting connections
    interval: 5s
    retries: 5

app:
  depends_on:
    postgres:
      condition: service_healthy  # app starts AFTER postgres is healthy
    redis:
      condition: service_healthy
    redpanda:
      condition: service_healthy
```

### Testcontainers — Integration Testing

Testcontainers spins up real Docker containers for integration tests. No mocks, no H2 in-memory database — your tests run against the exact same PostgreSQL, Redis, and Kafka that production uses.

```java
public abstract class BaseIntegrationTest {

    // Singleton pattern — containers are created ONCE and reused across all test classes
    static final PostgreSQLContainer<?> postgres;
    static final GenericContainer<?> redis;
    static final KafkaContainer kafka;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

        // Start all containers once
        postgres.start();
        redis.start();
        kafka.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Point Spring to the test containers
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
```

**Singleton container pattern** — Starting Docker containers is slow (3–5 seconds each). If each test class started its own containers, running 23 integration tests would take minutes. The static initializer block starts containers once when the first test class loads, and they're reused for all subsequent tests.

**`@DynamicPropertySource`** — Containers use random ports (so tests don't conflict with your local development database). `DynamicPropertySource` wires these random ports into Spring's configuration at test startup.

### Test Profile

```yaml
# application-test.yml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop    # create tables at start, drop at end (clean slate)
  cache:
    type: none                 # disable Redis caching (test DB state directly)

orderflow:
  outbox:
    poll-interval-ms: 999999999  # effectively disable the outbox poller in tests
  rate-limit:
    max-requests: 1000           # don't trigger rate limiting in tests
```

---

## 14. Design Patterns Used

### 1. Transactional Outbox Pattern
**Where:** `OutboxService` + `OutboxPublisher`
**Why:** Guarantees atomic writes to DB + event bus. Solves the dual-write problem.

### 2. Template Method Pattern
**Where:** `IdempotentConsumer` (abstract) → `PaymentConsumer`, `InventoryConsumer`, `NotificationConsumer`
**Why:** Deserialization, idempotency checking, and acknowledgment are identical across consumers. Only the business logic (`handleEvent()`) differs.

### 3. State Machine Pattern
**Where:** `OrderStatus` enum with `canTransitionTo()`
**Why:** Prevents invalid state transitions. Centralizes the rules instead of scattering `if` checks across services.

### 4. Cache-Aside Pattern
**Where:** `ProductService` with `@Cacheable` / `@CacheEvict` + Redis
**Why:** Reduces database load for frequently-read, rarely-written data (product catalog).

### 5. Filter Chain Pattern
**Where:** Spring Security filter chain — `CorrelationIdFilter` → `JwtAuthenticationFilter` → `RateLimitingFilter`
**Why:** Each filter handles one concern (tracing, auth, rate limiting) without knowing about the others.

### 6. Domain Event Pattern (Fat Events)
**Where:** `OrderEvent` record with full order data
**Why:** Consumers can act independently without querying back to the order service. Reduces coupling.

### 7. Repository Pattern
**Where:** Spring Data JPA repositories (`OrderRepository`, `ProductRepository`, `InventoryRepository`)
**Why:** Abstracts database access behind an interface. Swap implementations without touching business logic.

### 8. Builder Pattern
**Where:** Micrometer `Timer.builder()`, `Counter.builder()`, `Gauge.builder()`
**Why:** Construct complex objects step-by-step with readable code.

---

## 15. Quick Reference: How It All Connects

```
Customer places an order via POST /api/orders

→ Request enters the Filter Chain:
    1. CorrelationIdFilter — assigns X-Correlation-Id (or reuses caller's)
    2. JwtAuthenticationFilter — extracts Bearer token, validates signature,
       sets SecurityContext with userId and roles
    3. RateLimitingFilter — checks Redis counter (INCR + EXPIRE)
       → Over limit? 429 Too Many Requests, stops here
       → Under limit? Continue
    4. Spring Security AuthorizationFilter — checks .anyRequest().authenticated()
       → No valid JWT? 401 Unauthorized

→ OrderController.createOrder() — extracts userId from SecurityContext

→ OrderService.createOrder() — wrapped in @Transactional + Timer:
    1. Idempotency check — findByIdempotencyKey()
       → Duplicate? Return the existing order (no double-charge)
    2. Load user from PostgreSQL
    3. For each item:
       → Load product, check it's active
       → InventoryRepository.findByProductIdForUpdate() ← PESSIMISTIC LOCK
       → Inventory.reserve(quantity) — reserved += amount
    4. Create Order + OrderItems (CascadeType.ALL saves items automatically)
    5. OutboxService.saveOrderEvent(ORDER_CREATED) ← same transaction
    6. COMMIT — order + inventory reservation + outbox event: atomic

→ OutboxPublisher (@Scheduled, every 1 second):
    → Polls outbox_events WHERE published = false
    → Sends to Kafka topic "orderflow.order.events"
       (key = orderId → same partition → ordered)
    → Marks event as published

→ Kafka delivers to three independent consumer groups:

    PaymentConsumer (ORDER_CREATED):
      → Idempotency check (processed_events table)
      → totalAmount > $10,000 → FAILED (fraud)
      → Otherwise → CONFIRMED
      → OrderService.transitionOrder() → new outbox event

    InventoryConsumer (ORDER_CONFIRMED):
      → Idempotency check
      → confirmShipment(): quantity -= amount, reserved -= amount
      → The stock is now actually deducted

    InventoryConsumer (ORDER_FAILED or ORDER_CANCELLED):
      → Idempotency check
      → releaseStock(): reserved -= amount
      → The stock is available again

    NotificationConsumer (ALL events):
      → Idempotency check
      → Logs: "Your order #abc has been placed. Total: $50.00"
      → (In production: send email via SendGrid, push via FCM)

→ Prometheus scrapes /actuator/prometheus every 15s:
    → order.creation.time{quantile="0.95"} 0.045
    → outbox.pending.count 0
    → orders.created.total 10042
    → http.rate_limited.total 3

→ Health check at /actuator/health:
    → outbox: UP (pending_events: 0)
    → db: UP
    → redis: UP
```

### Order Cancellation Flow

```
Customer cancels via POST /api/orders/{id}/cancel

→ Same filter chain (auth, rate limit)
→ OrderService.cancelOrder():
    1. Load order, verify ownership
    2. Check status.isCancellable() → only PENDING or CONFIRMED
    3. Release inventory for each item
    4. order.transitionTo(CANCELLED) — state machine validates
    5. Write ORDER_CANCELLED to outbox (same transaction)
    6. COMMIT

→ InventoryConsumer (ORDER_CANCELLED):
    → releaseStock() — reserved -= amount
→ NotificationConsumer:
    → "Order #abc has been cancelled. Any charges will be refunded."
```

### Error Handling Across the Stack

```
EntityNotFoundException        → 404 Not Found
IllegalArgumentException      → 400 Bad Request
IllegalStateException          → 409 Conflict (duplicate email, invalid transition)
BadCredentialsException        → 401 Unauthorized
AccessDeniedException          → 403 Forbidden
OptimisticLockException        → 409 Conflict (concurrent modification)
InsufficientInventoryException → 422 Unprocessable Entity
DuplicateRequestException      → 409 Conflict (duplicate idempotency key)
Generic Exception              → 500 Internal Server Error

All wrapped in: ApiResponse<T> { success, data, message, error, timestamp }
```

---

*This document contains all concept explanations from the OrderFlow interview preparation session. Every technology is explained from first principles — what it is, why it exists, and how OrderFlow uses it — so you can explain any part of the system in an interview with confidence.*
